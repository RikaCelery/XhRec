package github.rikacelery.v3.components

import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.events.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

sealed interface SchedulerMsg
data class OnSchedulerEvent(val event: Any) : SchedulerMsg
data class SchedulerHandleCommand(val env: CommandEnvelope) : SchedulerMsg

data class ArmedRoom(
    val roomId: Long,
    val roomName: String,
    val quality: String,
    val pkey: String = "",
    val autoPay: Boolean = false
)

class SchedulerComponent(
    private val requestBus: RequestBus,
    private val sessionComponent: SessionComponent,
    eventBus: EventBus,
    parentScope: CoroutineScope
) : Actor<SchedulerMsg>("SchedulerComponent", eventBus, parentScope) {

    private val armed = ConcurrentHashMap<Long, ArmedRoom>()
    private var gracefulStop = false

    override suspend fun onStart(scope: CoroutineScope) {
        subscribe<RoomStatusChanged>(RoomStatusChanged::class)
        subscribe<RecordingStopped>(RecordingStopped::class)
        subscribe<DownloadError>(DownloadError::class)
        subscribe<WriterFatal>(WriterFatal::class)
        subscribe<AuthExpired>(AuthExpired::class)
        subscribe<CommandEnvelope>(CommandEnvelope::class)

    }

    override suspend fun wrapEvent(event: Any): SchedulerMsg? = when (event) {
        is RoomStatusChanged -> OnSchedulerEvent(event)
        is RecordingStopped -> OnSchedulerEvent(event)
        is DownloadError -> OnSchedulerEvent(event)
        is WriterFatal -> OnSchedulerEvent(event)
        is AuthExpired -> OnSchedulerEvent(event)
        is CommandEnvelope -> SchedulerHandleCommand(event)
        else -> null
    }

    override suspend fun handle(msg: SchedulerMsg) {
        when (msg) {
            is OnSchedulerEvent -> handleEvent(msg.event)
            is SchedulerHandleCommand -> {
                handleCommand(msg.env)
            }

        }
    }

    private suspend fun handleEvent(event: Any) {
        when (event) {
            is RoomStatusChanged -> {
                if (gracefulStop) return
                val a = armed[event.roomId] ?: return
                if (event.newStatus != "public" && event.newStatus != "groupShow") {
                    return
                }
                if (event.newStatus == "groupShow" && !a.autoPay) return
                logger.debug(
                    "Armed room {} ({}) became {}, starting recording",
                    event.roomId,
                    a.roomName,
                    event.newStatus
                )
                sessionComponent.tell(DoStart(event.roomId, a.roomName, a.quality, a.pkey))
            }

            is RecordingStopped -> {
                if (gracefulStop) return
                val a = armed[event.roomId]
                if (a != null) {
                    logger.info(
                        "Recording stopped for armed room {} ({}), re-arming after delay",
                        event.roomId,
                        a.roomName
                    )
                    scope.launch {
                        delay(30.seconds)
                        sessionComponent.tell(DoStart(event.roomId, a.roomName, a.quality, a.pkey))
                    }
                } else {
                    logger.debug("Recording stopped for room {}", event.roomId)
                }
            }

            is DownloadError -> logger.warn("Download error room ${event.roomId}: ${event.reason}")
            is WriterFatal -> {
                logger.error("Writer fatal room ${event.roomId}: ${event.error}"); armed.remove(event.roomId)
            }

            is AuthExpired -> logger.warn("Auth expired user ${event.userId}")
            else -> {}
        }
    }

    fun internalAdd(room: Long, name1: String, quality: String, pkey: String, isArmed: Boolean, autoPay: Boolean) {
        armed[room] = ArmedRoom(room, name1, quality, pkey, autoPay)
        if (isArmed) logger.info("Room {} ({}) armed and waiting", name1, room)
    }

    private suspend fun handleCommand(env: CommandEnvelope) {
        val ack = when (env.command) {
            is StartRecordingCmd -> {
                if (armed.contains(env.command.roomId)) {
                    OkResponse
                } else try {
                    val roomName = requestBus.request<RoomNameResponse>(GetRoomName(env.command.roomId)).name
                    val config = requestBus.request<RoomConfigResponse>(GetRoomConfig(env.command.roomId))
                    armed[env.command.roomId] =
                        ArmedRoom(env.command.roomId, roomName, config.quality, config.pkey, config.autoPay)
                    requestBus.request<OkResponse>(RefreshRoomCmd(env.command.roomId))
                    OkResponse
                } catch (_: Exception) {
                    ErrorResponse("failed to start recording")
                }
            }

            is StopRecordingCmd -> {
                sessionComponent.tell(DoStop(env.command.roomId)); OkResponse
            }

            is ActivateRecordingCmd -> {
                try {
                    val name = requestBus.request<RoomNameResponse>(GetRoomName(env.command.roomId)).name
                    val config = requestBus.request<RoomConfigResponse>(GetRoomConfig(env.command.roomId))
                    armed[env.command.roomId] =
                        ArmedRoom(env.command.roomId, name, config.quality, config.pkey, config.autoPay)
                    logger.info("Room {} ({}) activated (armed)", name, env.command.roomId)
                    requestBus.request<OkResponse>(RefreshRoomCmd(env.command.roomId))
                } catch (_: Exception) {
                }
                OkResponse
            }

            is DeactivateCmd -> {
                armed.remove(env.command.roomId)
                logger.info("Room {} deactivated", env.command.roomId)
                sessionComponent.tell(DoStop(env.command.roomId))
                OkResponse
            }

            is BreakCmd -> {
                sessionComponent.tell(DoBreak(env.command.roomId, env.command.reason))
                OkResponse
            }

            is GetArmedRoomIds -> armed.keys().toList()
            is ShutdownCmd -> {
                gracefulStop = true
                armed.forEach { (id, _) ->
                    sessionComponent.tell(DoBreak(id, EndReason.UserStop))
                }
                OkResponse
            }

            else -> return
        }
        eventBus.publish(CommandAck(env.id, ack))
    }

}
