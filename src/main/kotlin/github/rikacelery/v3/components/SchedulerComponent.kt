package github.rikacelery.v3.components

import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.events.*
import github.rikacelery.v3.utils.ClientManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
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

    // latest known room status (public/groupShow/off/idle...) and stream lifecycle status
    private val roomStatuses = ConcurrentHashMap<Long, String>()
    private val streamStatuses = ConcurrentHashMap<Long, String>()
    // fallback start jobs scheduled when a room goes public but the stream event is late/missed
    private val pendingStarts = ConcurrentHashMap<Long, Job>()
    private val streamReadyTimeoutMs = 15_000L

    override suspend fun onStart(scope: CoroutineScope) {
        subscribe<RoomStatusChanged>(RoomStatusChanged::class)
        subscribe<StreamStatusChanged>(StreamStatusChanged::class)
        subscribe<RecordingStopped>(RecordingStopped::class)
        subscribe<DownloadError>(DownloadError::class)
        subscribe<WriterFatal>(WriterFatal::class)
        subscribe<AuthExpired>(AuthExpired::class)
        subscribe<CommandEnvelope>(CommandEnvelope::class)

    }

    override suspend fun wrapEvent(event: Any): SchedulerMsg? = when (event) {
        is RoomStatusChanged -> OnSchedulerEvent(event)
        is StreamStatusChanged -> OnSchedulerEvent(event)
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
                roomStatuses[event.roomId] = event.newStatus
                if (event.newStatus == "public" || (event.newStatus == "groupShow" && a.autoPay)) {
                    logger.debug("Armed room {} ({}) became {}, waiting for stream", event.roomId, a.roomName, event.newStatus)
                    val streamStatus = streamStatuses[event.roomId]
                    when {
                        // stream already distributing → start immediately
                        streamStatus == "distributing" -> tryStartRecording(event.roomId, a)
                        // stream status unknown (e.g. first boot: already streaming, no event will come)
                        // → probe the master playlist; start at once when it is reachable
                        streamStatus == null -> probeAndStart(event.roomId, a)
                        // known but not distributing yet (probing) → wait for the distributing event
                        else -> scheduleStart(event.roomId, a)
                    }
                } else {
                    cancelPendingStart(event.roomId)
                }
            }

            is StreamStatusChanged -> {
                streamStatuses[event.roomId] = event.newStatus
                if (gracefulStop) return
                val a = armed[event.roomId] ?: return
                if (event.newStatus == "distributing" && canRecord(event.roomId, a)) {
                    cancelPendingStart(event.roomId)
                    tryStartRecording(event.roomId, a)
                }
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

    /** True when the armed room is currently recordable (public, or groupShow with auto-pay). */
    private fun canRecord(roomId: Long, a: ArmedRoom): Boolean {
        val status = roomStatuses[roomId] ?: return false
        if (status != "public" && status != "groupShow") return false
        if (status == "groupShow" && !a.autoPay) return false
        return true
    }

    /** Start recording now (guard: graceful stop / room recordable). */
    private suspend fun tryStartRecording(roomId: Long, a: ArmedRoom) {
        if (gracefulStop) return
        if (!canRecord(roomId, a)) return
        logger.debug("Starting recording for {} ({})", a.roomName, roomId)
        sessionComponent.tell(DoStart(roomId, a.roomName, a.quality, a.pkey))
    }

    /** Probe stream readiness (first boot / unknown status): start now if ready, else wait. */
    private fun probeAndStart(roomId: Long, a: ArmedRoom) {
        if (pendingStarts.containsKey(roomId)) return
        val job = scope.launch {
            val ready = try {
                requestBus.request<ConfigResponse>(ProbeStreamReady(roomId, a.roomName, a.quality)).value as? Boolean ?: false
            } catch (e: Exception) {
                false
            }
            pendingStarts.remove(roomId)
            if (ready) {
                tryStartRecording(roomId, a)
            } else {
                // not ready yet — wait for the distributing event or the timeout fallback
                scheduleStart(roomId, a)
            }
        }
        pendingStarts[roomId] = job
    }

    /** Arm a fallback start after [streamReadyTimeoutMs] in case the stream event is late or missed. */
    private fun scheduleStart(roomId: Long, a: ArmedRoom) {
        if (pendingStarts.containsKey(roomId)) return
        val job = scope.launch {
            delay(streamReadyTimeoutMs.milliseconds)
            pendingStarts.remove(roomId)
            tryStartRecording(roomId, a)
        }
        pendingStarts[roomId] = job
    }

    private fun cancelPendingStart(roomId: Long) {
        pendingStarts.remove(roomId)?.cancel()
    }

    fun internalAdd(room: Long, name1: String, quality: String, pkey: String, isArmed: Boolean, autoPay: Boolean) {
        armed[room] = ArmedRoom(room, name1, quality, pkey, autoPay)
        if (isArmed) logger.info("Room {} ({}) armed and waiting", name1, room)
    }

    private suspend fun handleCommand(env: CommandEnvelope) {
        val ack = when (env.command) {
            is ActivateRecordingCmd -> {
                if (armed.contains(env.command.roomId)) {
                    logger.info("Room {} ({}) already activated (armed)", name, env.command.roomId)
                } else {
                    val name = requestBus.request<RoomNameResponse>(GetRoomName(env.command.roomId)).name
                    val config = requestBus.request<RoomConfigResponse>(GetRoomConfig(env.command.roomId))
                    armed[env.command.roomId] =
                        ArmedRoom(env.command.roomId, name, config.quality, config.pkey, config.autoPay)
                    logger.info("Room {} ({}) activated (armed)", name, env.command.roomId)
                    requestBus.request<OkResponse>(RefreshRoomCmd(env.command.roomId))
                }
                OkResponse
            }

            is DeactivateCmd -> {
                armed.remove(env.command.roomId)
                cancelPendingStart(env.command.roomId)
                logger.info("Room {} deactivated", env.command.roomId)
                sessionComponent.tell(DoStop(env.command.roomId))
                ClientManager.removeRoomClients(env.command.roomId)
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
                    ClientManager.removeRoomClients(id)
                }
                OkResponse
            }

            else -> return
        }
        eventBus.publish(CommandAck(env.id, ack))
    }

}
