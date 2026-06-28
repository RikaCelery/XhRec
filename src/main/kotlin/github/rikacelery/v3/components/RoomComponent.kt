package github.rikacelery.v3.components

import github.rikacelery.v3.api.ApiClient
import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.data.Room
import github.rikacelery.v3.events.*
import github.rikacelery.v3.exceptions.DeletedException
import github.rikacelery.v3.exceptions.RenameException
import github.rikacelery.v3.utils.PathSingle
import github.rikacelery.v3.utils.SensitiveStringRegistry
import github.rikacelery.v3.utils.asString
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed interface RoomMsg
data class OnRoomEvent(val event: Any) : RoomMsg
data class HandleRoomCommand(val env: CommandEnvelope) : RoomMsg
object RefreshRooms : RoomMsg

class RoomComponent(
    private val apiClient: ApiClient,
    private val listConfPath: String,
    private val platformHost: String,
    private val requestBus: RequestBus,
    eventBus: EventBus,
    parentScope: CoroutineScope
) : Actor<RoomMsg>("RoomComponent", eventBus, parentScope) {

    private val rooms = ConcurrentHashMap<Long, Room>()
    private var ready = false
    private var saveDebounceJob: Job? = null
    @Volatile private var stopRefresh = false

    suspend fun setReady() {
        tell(RefreshRooms)
        ready = true
    }

    override suspend fun onStart(scope: CoroutineScope) {
        subscribe<RoomStatusChanged>(RoomStatusChanged::class)
        subscribe<CommandEnvelope>(CommandEnvelope::class)
        subscribe<PersistConfig>(PersistConfig::class)
        scope.launch {
            tell(RefreshRooms)
            while (isActive && !stopRefresh) {
                delay((30).seconds); tell(RefreshRooms)
            }
        }
    }

    override suspend fun wrapEvent(event: Any): RoomMsg? = when (event) {
        is RoomStatusChanged -> OnRoomEvent(event)
        is CommandEnvelope -> HandleRoomCommand(event)
        is PersistConfig -> OnRoomEvent(event)
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
                else -> {}
            }

            is HandleRoomCommand -> {
                scope.launch {
                    try {
                        handleCommand(msg.env)
                    } catch (e: Exception) {
                        eventBus.publish(CommandAck(msg.env.id, ErrorResponse(e.message ?: "error")))
                    }
                }
            }

            is RefreshRooms -> scope.launch{
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
                        rooms[id] = Room(id, name, cmd.quality, cmd.timeLimit, cmd.sizeLimitBytes, cmd.autoPay, null, pkey = cmd.pkey)
                        SensitiveStringRegistry.mask(name)
                        logger.info("Room added: id={}, name={}, quality={}", id, name, cmd.quality)
                        eventBus.publish(RoomAdded(id, name))
                        RoomNameResponse(name)
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to add room '{}': {}", cmd.name, e.message)
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
                    } catch (_: Exception) {}
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
        if (!refreshLock.tryLock()) {
            return
        }
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
                rooms[room.id] = room.copy(name = e.newName)
                eventBus.publish(RoomRenamed(room.id, oldName, e.newName))
                logger.info("Room ${room.id} renamed: $oldName -> ${e.newName}")
            } catch (_: DeletedException) {
                rooms.remove(room.id)
                eventBus.publish(RoomRemoved(room.id, room.name))
                logger.info("Room ${room.id} deleted: ${room.name}")
            } catch (e: Exception) {
                logger.warn("refreshAll error room ${room.id}: ${e.message}")
            }
        }
        refreshLock.unlock()
    }

    fun internalAdd(
        id: Long,
        name: String,
        quality: String,
        timeLimit: Duration,
        sizeLimitBytes: Long,
        autoPay: Boolean,
        pkey: String = ""
    ) {
        rooms[id] = Room(id, name, quality, timeLimit, sizeLimitBytes, autoPay, null, pkey = pkey)
    }

    fun snapshotRooms(): List<Room> = rooms.values.map { it.copy() }


    private suspend fun saveListConf() {
        try {
            val armedIds = requestBus.request<List<Long>>(GetArmedRoomIds).toSet()
            val file = File(listConfPath)
            synchronized(file) {
                file.writeText(rooms.values.joinToString("\n") { room ->
                    val prefix = if (room.id in armedIds) "" else "#"
                    val sb = StringBuilder("${prefix}https://$platformHost/${room.name} q:${room.quality}")
                    if (room.timeLimit != Duration.INFINITE) sb.append(" limit:${room.timeLimit.inWholeSeconds}")
                    if (room.sizeLimitBytes > 0) sb.append(" size:${formatSize(room.sizeLimitBytes)}")
                    if (room.pkey.isNotBlank()) sb.append(" pkey:${room.pkey}")
                    if (room.autoPay) sb.append(" autopay")
                    sb.toString()
                }.let { lines -> if (lines.isNotEmpty()) lines + "\n" else "" })
            }
        } catch (e: Exception) {
            logger.warn("Failed to save list.conf: ${e.message}")
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L*1024*1024*1024 -> "${bytes / (1024L*1024*1024*1024)}Ti"
        bytes >= 1024*1024*1024 -> "${bytes / (1024*1024*1024)}Gi"
        bytes >= 1024*1024 -> "${bytes / (1024*1024)}Mi"
        bytes >= 1024 -> "${bytes / 1024}Ki"
        else -> "${bytes}Bi"
    }
}
