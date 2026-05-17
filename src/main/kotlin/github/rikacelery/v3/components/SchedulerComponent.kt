package github.rikacelery.v3.components

import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.events.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.set
import kotlin.time.Duration.Companion.seconds

sealed interface SchedulerMsg
data class OnSchedulerEvent(val event: Any) : SchedulerMsg
data class SchedulerHandleCommand(val env: CommandEnvelope) : SchedulerMsg
object LoopListen : SchedulerMsg

data class ArmedRoom(val roomId: Long, val roomName: String, val quality: String)

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

        scope.launch {
            while (isActive) {
                delay(1.seconds); tell(LoopListen)
            }
        }
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

            is LoopListen -> loopListen()
        }
    }

    private suspend fun handleEvent(event: Any) {
        when (event) {
            is RoomStatusChanged -> {
                if (gracefulStop) return
                val a = armed[event.roomId] ?: return
                if (event.newStatus == "public" || event.newStatus == "groupShow") {
                    logger.debug(
                        "Armed room {} ({}) became {}, starting recording",
                        event.roomId,
                        a.roomName,
                        event.newStatus
                    )
                    eventBus.publish(RecordingStarted(event.roomId))
                    sessionComponent.tell(DoStart(event.roomId, a.roomName, a.quality))
                }
            }

            is RecordingStopped -> {
                logger.debug("Recording stopped for room {}, removing from armed", event.roomId)
                armed.remove(event.roomId)
            }

            is DownloadError -> logger.warn("Download error room ${event.roomId}: ${event.reason}")
            is WriterFatal -> {
                logger.error("Writer fatal room ${event.roomId}: ${event.error}"); armed.remove(event.roomId)
            }

            is AuthExpired -> logger.warn("Auth expired user ${event.userId}")
            else -> {}
        }
    }

    fun internalAdd(room: Long, name1: String, quality: String, isArmed: Boolean) {
        armed[room] = ArmedRoom(room, name1, quality)
    }

    private suspend fun handleCommand(env: CommandEnvelope) {
        val ack = when (env.command) {
            is StartRecordingCmd -> {
                try {
                    val name = requestBus.request<RoomNameResponse>(GetRoomName(env.command.roomId)).name
                    val config = requestBus.request<RoomConfigResponse>(GetRoomConfig(env.command.roomId))
                    armed[env.command.roomId] = ArmedRoom(env.command.roomId, name, config.quality)
                    eventBus.publish(RecordingStarted(env.command.roomId))
                    sessionComponent.tell(DoStart(env.command.roomId, name, config.quality))
                } catch (e: Exception) { /* room offline, armed and waiting */
                }
                OkResponse
            }

            is StopRecordingCmd -> {
                sessionComponent.tell(DoStop(env.command.roomId)); OkResponse
            }

            is ActivateRecordingCmd -> {
                try {
                    val name = requestBus.request<RoomNameResponse>(GetRoomName(env.command.roomId)).name
                    val config = requestBus.request<RoomConfigResponse>(GetRoomConfig(env.command.roomId))
                    armed[env.command.roomId] = ArmedRoom(env.command.roomId, name, config.quality)
                } catch (_: Exception) {
                }
                OkResponse
            }

            is DeactivateCmd -> {
                armed.remove(env.command.roomId)
                sessionComponent.tell(DoStop(env.command.roomId))
                OkResponse
            }

            is BreakCmd -> {
                sessionComponent.tell(DoBreak(env.command.roomId))
                OkResponse
            }

            is ShutdownCmd -> {
                gracefulStop = true
                eventBus.publish("ServerShutdown")
                OkResponse
            }

            else -> return
        }
        eventBus.publish(CommandAck(env.id, ack))
    }

    private suspend fun loopListen() {
        if (gracefulStop) return
        armed.forEach { (roomId, a) ->
            try {
                val status = requestBus.request<RoomStatusResponse>(GetRoomStatus(roomId))
                if (status.status == "public" || status.status == "groupShow") {
                    eventBus.publish(RecordingStarted(roomId))
                    sessionComponent.tell(DoStart(roomId, a.roomName, a.quality))
                }
            } catch (_: Exception) {
            }
        }
    }
}
