package github.rikacelery.v3.components

import github.rikacelery.v3.api.ApiClient
import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.data.Hosts
import github.rikacelery.v3.data.Room
import github.rikacelery.v3.events.*
import github.rikacelery.v3.exceptions.DeletedException
import github.rikacelery.v3.exceptions.RenameException
import github.rikacelery.v3.utils.PathSingle
import github.rikacelery.v3.utils.SensitiveStringRegistry
import github.rikacelery.v3.utils.asString
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

sealed interface RoomMsg
data class OnRoomEvent(val event: Any) : RoomMsg
data class HandleRoomCommand(val env: CommandEnvelope) : RoomMsg
object RefreshRooms : RoomMsg

class RoomComponent(
    private val apiClient: ApiClient,
    private val listConfPath: String,
    private val requestBus: RequestBus,
    eventBus: EventBus,
    parentScope: CoroutineScope,
    /** Slow catch-up cadence; live status changes arrive via WebSocket */
    private val refreshInterval: Duration = 5.minutes
) : Actor<RoomMsg>("RoomComponent", eventBus, parentScope) {

    private val rooms = ConcurrentHashMap<Long, Room>()
    private var ready = false
    private var saveDebounceJob: Job? = null
    private var refreshDebounceJob: Job? = null
    private val saveLock = Mutex()

    /** Debounce window for WS-triggered status refresh, avoids an API storm when WS flaps. */
    private val refreshDebounceMs = 1_500L

    @Volatile
    private var stopRefresh = false

    suspend fun setReady() {
        tell(RefreshRooms)
        ready = true
    }

    override suspend fun onStart(scope: CoroutineScope) {
        subscribe<RoomStatusChanged>(RoomStatusChanged::class)
        subscribe<CommandEnvelope>(CommandEnvelope::class)
        subscribe<PersistConfig>(PersistConfig::class)
        subscribe<WsDisconnected>(WsDisconnected::class)
        subscribe<WsReconnected>(WsReconnected::class)
        scope.launch {
            tell(RefreshRooms)
            while (isActive && !stopRefresh) {
                delay(refreshInterval); tell(RefreshRooms)
            }
        }
    }

    override suspend fun wrapEvent(event: Any): RoomMsg? = when (event) {
        is RoomStatusChanged -> OnRoomEvent(event)
        is CommandEnvelope -> HandleRoomCommand(event)
        is PersistConfig -> OnRoomEvent(event)
        is WsDisconnected -> OnRoomEvent(event)
        is WsReconnected -> OnRoomEvent(event)
        else -> null
    }

    override suspend fun handle(msg: RoomMsg) {
        if (!scope.isActive) return
        when (msg) {
            is OnRoomEvent -> when (val event = msg.event) {
                is RoomStatusChanged -> {
                    rooms[event.roomId]?.let {
                        rooms[event.roomId] = it.copy(status = event.newStatus)
                        logger.debug("Room {} status: {} -> {}", event.roomId, event.oldStatus, event.newStatus)
                    }
                }

                is PersistConfig -> {
                    saveDebounceJob?.cancel()
                    saveDebounceJob = scope.launch {
                        delay(1.seconds)
                        saveListConf()
                    }
                }

                is WsDisconnected, is WsReconnected -> {
                    logger.debug("WS state changed ({}), scheduling debounced refreshAll", event::class.simpleName)
                    refreshDebounceJob?.cancel()
                    refreshDebounceJob = scope.launch {
                        delay(refreshDebounceMs)
                        tell(RefreshRooms)
                    }
                }

                else -> {}
            }

            is HandleRoomCommand -> {
                scope.launch {
                    try {
                        handleCommand(msg.env)
                    } catch (e: Exception) {
                        logger.error("handleCommand failed for ${msg.env.command}", e)
                        eventBus.publish(CommandAck(msg.env.id, ErrorResponse(e.message ?: "error")))
                    }
                }
            }

            is RefreshRooms -> scope.launch {
                refreshAll()
            }

        }
    }

    private suspend fun handleCommand(env: CommandEnvelope) {
        val ack = when (val cmd = env.command) {
            is GetRoomName -> {
                val r = rooms[cmd.roomId]
                    ?: throw NoSuchElementException("room ${cmd.roomId} not found")
                RoomNameResponse(r.name)
            }

            is GetRoomConfig -> {
                val r = rooms[cmd.roomId]
                    ?: throw NoSuchElementException("room ${cmd.roomId} not found")
                RoomConfigResponse(
                    r.quality,
                    r.timeLimit,
                    r.sizeLimitBytes,
                    r.autoPay,
                    r.pkey
                )
            }

            is SetRoomQuality -> {
                rooms[cmd.roomId]?.let { rooms[it.id] = it.copy(quality = cmd.quality) }
                logger.info("User changed quality for room {} to {}", cmd.roomId, cmd.quality)
                eventBus.publish(QualityChangeRequested(cmd.roomId, cmd.quality))
                OkResponse
            }

            is SetRoomTimeLimit -> {
                rooms[cmd.roomId]?.let { rooms[it.id] = it.copy(timeLimit = cmd.limit) }
                eventBus.publish(RoomTimeLimitChanged(cmd.roomId, cmd.limit))
                OkResponse
            }

            is SetRoomSizeLimit -> {
                rooms[cmd.roomId]?.let { rooms[it.id] = it.copy(sizeLimitBytes = cmd.limitBytes) }
                eventBus.publish(RoomSizeLimitChanged(cmd.roomId, cmd.limitBytes))
                OkResponse
            }

            is SetRoomAutoPay -> {
                rooms[cmd.roomId]?.let { rooms[it.id] = it.copy(autoPay = cmd.autoPay) }; OkResponse
            }

            is AddRoom -> {
                if (!ready) {
                    ErrorResponse("system initializing, please retry")
                } else try {
                    val (id, name) = apiClient.getRoomFromUrlOrSlug(cmd.name)
                    if (rooms.containsKey(id) || rooms.values.any { it.name.equals(name, true) }) {
                        logger.warn("Duplicate room: id={}, name={}", id, name)
                        ErrorResponse("Exist $name")
                    } else {
                        rooms[id] = Room(
                            id,
                            name,
                            cmd.quality,
                            cmd.timeLimit,
                            cmd.sizeLimitBytes,
                            cmd.autoPay,
                            null,
                            pkey = cmd.pkey
                        )
                        SensitiveStringRegistry.mask(name)
                        logger.info("Room added: id={}, name={}, quality={}", id, name, cmd.quality)
                        eventBus.publish(RoomAdded(id, name))
                        RoomNameResponse(name)
                    }
                } catch (e: Exception) {
                    logger.error("Failed to add room '{}': {}", cmd.name, e.message, e)
                    ErrorResponse("failed to add room: ${e.message}")
                }
            }

            is RemoveRoom -> {
                if (!ready) {
                    ErrorResponse("system initializing, please retry")
                } else {
                    val removed = rooms.remove(cmd.roomId)
                    logger.info("Room removed: id={}, name={}", cmd.roomId, removed?.name)
                    eventBus.publish(RoomRemoved(cmd.roomId, removed?.name ?: ""))
                    OkResponse
                }
            }

            is GetRooms -> rooms.values.map { it.copy() }
            is RefreshRoomCmd -> {
                scope.launch {
                    val room = rooms[cmd.roomId] ?: return@launch
                    try {
                        val info = apiClient.roomFetchBroadcastInfo(room.name)
                        val status = info.PathSingle("item.status").asString()
                        if (status != room.status) {
                            rooms[room.id] = room.copy(status = status)
                        }
                        eventBus.publish(RoomStatusChanged(room.id, room.status, status))
                    } catch (e: Exception) {
                        logger.error("Failed to refresh status for room ${cmd.roomId}", e)
                    }
                }
                OkResponse
            }

            is ShutdownCmd -> {
                stopRefresh = true
                OkResponse
            }

            else -> return
        }
        eventBus.publish(CommandAck(env.id, ack))
    }

    private val refreshLock = Mutex()
    private suspend fun refreshAll() {
        if (refreshLock.isLocked) {
            if (logger.isTraceEnabled)
                logger.trace("already refreshing.")
            return
        }
        refreshLock.withLock {
            rooms.values.forEach { room ->
                try {
                    val info = apiClient.roomFetchBroadcastInfo(room.name)
                    val status = info.PathSingle("item.status").asString()
                    val oldStatus = room.status
                    if (status != oldStatus) {
                        rooms[room.id] = room.copy(status = status)
                        eventBus.publish(RoomStatusChanged(room.id, oldStatus, status))
                        logger.debug("refreshAll: room {} status {} -> {}", room.id, oldStatus, status)
                    }
                } catch (e: RenameException) {
                    val oldName = room.name
                    logger.error("Room ${room.id} renamed: $oldName -> ${e.newName}", e)
                    rooms[room.id] = room.copy(name = e.newName)
                    eventBus.publish(RoomRenamed(room.id, oldName, e.newName))
                } catch (e: DeletedException) {
                    logger.error("Room ${room.id} deleted: ${room.name}", e)
                    rooms.remove(room.id)
                    eventBus.publish(RoomRemoved(room.id, room.name))
                } catch (e: Exception) {
                    logger.error("refreshAll error room ${room.id}: ${e.message}", e)
                }
            }
        }
    }

    suspend fun internalAdd(
        id: Long,
        name: String,
        quality: String,
        timeLimit: Duration,
        sizeLimitBytes: Long,
        autoPay: Boolean,
        pkey: String = ""
    ) {
        rooms[id] = Room(id, name, quality, timeLimit, sizeLimitBytes, autoPay, null, pkey = pkey)
        // let LiveEventSource subscribe the room's status channels
        eventBus.publish(RoomAdded(id, name))
    }


    private suspend fun saveListConf() {
        // Serialize saves: debounce jobs may overlap when cancellation races an
        // in-flight save, and concurrent writeText to one file corrupts it.
        saveLock.withLock {
            try {
                val armedIds = requestBus.request<List<Long>>(GetArmedRoomIds).toSet()
                val file = File(listConfPath)
                val content = rooms.values.joinToString("\n") { room ->
                    val prefix = if (room.id in armedIds) "" else "#"
                    val sb = StringBuilder("${prefix}https://" + Hosts.primaryPlatformHost() + "/" + room.name + " q:" + room.quality)
                    if (room.timeLimit != Duration.INFINITE) sb.append(" limit:${room.timeLimit.inWholeSeconds}")
                    if (room.sizeLimitBytes > 0) sb.append(" size:${formatSize(room.sizeLimitBytes)}")
                    if (room.pkey.isNotBlank()) sb.append(" pkey:${room.pkey}")
                    if (room.autoPay) sb.append(" autopay")
                    sb.toString()
                }.let { lines -> if (lines.isNotEmpty()) lines + "\n" else "" }
                withContext(Dispatchers.IO) {
                    file.writeText(content)
                }
            } catch (e: Exception) {
                logger.error("Failed to save list.conf: ${e.message}", e)
            }
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 * 1024 -> "${bytes / (1024L * 1024 * 1024 * 1024)}Ti"
        bytes >= 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024 * 1024)}Gi"
        bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)}Mi"
        bytes >= 1024 -> "${bytes / 1024}Ki"
        else -> "${bytes}Bi"
    }
}
