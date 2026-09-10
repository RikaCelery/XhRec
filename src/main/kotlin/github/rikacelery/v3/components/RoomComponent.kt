package github.rikacelery.v3.components

import github.rikacelery.v3.api.ApiClient
import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.data.FavoriteCandidate
import github.rikacelery.v3.data.Hosts
import github.rikacelery.v3.data.Room
import github.rikacelery.v3.data.RoomSettings
import github.rikacelery.v3.data.RuntimeTuning
import github.rikacelery.v3.data.User
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
    private val runtimeTuning: RuntimeTuning = RuntimeTuning(),
    private val roomStatusFetcher: suspend (String) -> String = { roomName ->
        apiClient.roomFetchBroadcastInfo(roomName).PathSingle("item.status").asString()
    }
) : Actor<RoomMsg>("RoomComponent", eventBus, parentScope) {

    private val rooms = ConcurrentHashMap<Long, Room>()
    private var ready = false
    private var saveDebounceJob: Job? = null
    private var refreshDebounceJob: Job? = null
    private val saveLock = Mutex()

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
        subscribe<StopEvent>(StopEvent::class)
        scope.launch {
            tell(RefreshRooms)
            while (isActive && !stopRefresh) {
                delay(runtimeTuning.roomPollInterval); tell(RefreshRooms)
            }
        }
    }

    override suspend fun wrapEvent(event: Any): RoomMsg? = when (event) {
        is RoomStatusChanged -> OnRoomEvent(event)
        is CommandEnvelope -> HandleRoomCommand(event)
        is PersistConfig -> OnRoomEvent(event)
        is WsDisconnected -> OnRoomEvent(event)
        is WsReconnected -> OnRoomEvent(event)
        is StopEvent -> OnRoomEvent(event)
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
                        delay(runtimeTuning.roomRefreshDebounce)
                        tell(RefreshRooms)
                    }
                }

                is StopEvent -> {
                    logger.info("Stop event received: room refresh stopped")
                    stopRefresh = true
                }

                else -> {}
            }

            is HandleRoomCommand -> if (msg.env.command is RefreshRoomCmd) {
                try {
                    handleCommand(msg.env)
                } catch (e: Exception) {
                    logger.error("handleCommand failed for ${msg.env.command}", e)
                    eventBus.publish(CommandAck(msg.env.id, ErrorResponse(e.message ?: "error")))
                }
            } else {
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
                RoomConfigResponse(r.settings())
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

            is SetRoomFilter -> {
                rooms[cmd.roomId]?.let { room ->
                    val updated = when (cmd.kind) {
                        RecordingFilterKind.PUBLIC -> room.copy(recordPublic = cmd.value)
                        RecordingFilterKind.FREE_SPY -> room.copy(recordFreeSpy = cmd.value)
                        RecordingFilterKind.TICKET -> room.copy(autoPayTicket = cmd.value)
                        RecordingFilterKind.PAID_SPY -> room.copy(autoPaySpy = cmd.value)
                    }
                    rooms[room.id] = updated
                    // an armed room refreshes its own copy only through a preconfig attempt, and a
                    // room that is no longer recordable never makes one: push the change instead
                    eventBus.publish(RoomSettingsChanged(updated.id, updated.settings(), updated.status))
                }
                OkResponse
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
                        rooms[id] = newRoom(id, name, cmd.settings)
                        SensitiveStringRegistry.mask(name)
                        logger.info("Room added: id={}, name={}, quality={}", id, name, cmd.settings.quality)
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

            // favorites import talks to the platform: handle() runs it off the actor loop
            is GetFavoriteCandidates -> {
                val users = requestBus.request<UsersResponse>(GetUsers, timeoutMs = FAVORITES_COMMAND_TIMEOUT_MS)
                    .users.filter { it.userId in cmd.userIds }
                FavoriteCandidatesResponse(favoriteCandidates(users))
            }
            is ImportFavorites -> FavoritesImportResponse(importFavorites(cmd.modelIds))

            is RefreshRoomCmd -> {
                val room = rooms[cmd.roomId]
                    ?: throw NoSuchElementException("room ${cmd.roomId} not found")
                refreshRoomStatus(room)
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

    private suspend fun refreshRoomStatus(room: Room) {
        val status = roomStatusFetcher(room.name)
        val current = rooms[room.id] ?: return
        if (status != current.status) {
            rooms[room.id] = current.copy(status = status)
            eventBus.publish(RoomStatusChanged(room.id, current.status, status))
            logger.debug("refreshRoom: room {} status {} -> {}", room.id, current.status, status)
        }
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

    /** A room carrying every field of [settings], with no status seen yet. */
    private fun newRoom(id: Long, name: String, settings: RoomSettings): Room = Room(
        id = id,
        name = name,
        quality = settings.quality,
        timeLimit = settings.timeLimit,
        sizeLimitBytes = settings.sizeLimitBytes,
        recordPublic = settings.recordPublic,
        recordFreeSpy = settings.recordFreeSpy,
        autoPayTicket = settings.autoPayTicket,
        autoPaySpy = settings.autoPaySpy,
        lastSeen = null,
        pkey = settings.pkey
    )

    suspend fun internalAdd(
        id: Long,
        name: String,
        settings: RoomSettings,
    ) {
        rooms[id] = newRoom(id, name, settings)
        // let LiveEventSource subscribe the room's status channels
        eventBus.publish(RoomAdded(id, name))
    }

    /**
     * The platform favorites of [users] as import candidates, for the WebUI to pick from.
     *
     * Favorites only carry model ids, so unknown ids are resolved to their room name; a room
     * that is already known reuses the name it has and is flagged [FavoriteCandidate.existing]
     * instead of being offered again. Ids that cannot be resolved, and accounts whose favorites
     * request fails, are skipped.
     */
    suspend fun favoriteCandidates(users: List<User>): List<FavoriteCandidate> {
        val favoriteIds = users.flatMap { fetchFavoriteIds(it) }.distinct()
        val known = rooms
        val existing = favoriteIds.mapNotNull { id -> known[id]?.let { FavoriteCandidate(id, it.name, existing = true) } }
        val fresh = resolveRoomNames(favoriteIds.filterNot { known.containsKey(it) })
            .map { (id, name) -> FavoriteCandidate(id, name, existing = false) }
        return (existing + fresh).sortedBy { it.name.lowercase() }
    }

    /**
     * Imports the picked favorites as rooms, **disarmed** — the scheduler is never told about
     * them, so an import only widens the room list. Models that are already rooms are left
     * untouched, and the changes are persisted to list.conf.
     *
     * @return the names of the rooms that were added
     */
    suspend fun importFavorites(modelIds: List<Long>): List<String> {
        val known = rooms.keys
        val added = resolveRoomNames(modelIds.distinct().filterNot { it in known }).map { (id, name) ->
            SensitiveStringRegistry.mask(name)
            internalAdd(id, name, RoomSettings(quality = FAVORITES_DEFAULT_QUALITY))
            logger.info("Favorite imported as room: id={}, name={}", id, name)
            name
        }
        if (added.isNotEmpty()) {
            logger.info("Imported {} favorite(s) as disarmed room(s)", added.size)
            // persist the new rooms (disarmed, so they are written commented out)
            eventBus.publish(PersistConfig)
        }
        return added
    }

    private suspend fun fetchFavoriteIds(user: User): List<Long> = try {
        apiClient.userFetchFavoriteIds(user)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("Could not read the favorites of user {}: {}", user.userId, e.message)
        emptyList()
    }

    /**
     * Resolves favorite model ids to `id to name`, in bounded batches: a favorites list can be
     * long and must not turn into one burst of requests on the platform.
     */
    private suspend fun resolveRoomNames(modelIds: List<Long>): List<Pair<Long, String>> {
        val resolved = mutableListOf<Pair<Long, String>>()
        modelIds.chunked(FAVORITES_LOOKUP_CONCURRENCY).forEach { chunk ->
            val names = coroutineScope {
                chunk.map { id ->
                    async {
                        try {
                            apiClient.roomNameFromId(id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.warn("Could not resolve the name of model {}: {}", id, e.message)
                            null
                        }
                    }
                }.awaitAll()
            }
            chunk.zip(names).forEach { (id, name) ->
                if (name == null) logger.warn("Skipping favorite {}: the model name is unknown", id)
                else resolved += id to name
            }
        }
        return resolved
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
                    // filters are opt-out tokens: a line without them keeps the defaults
                    if (!room.recordPublic) sb.append(" nopublic")
                    if (!room.recordFreeSpy) sb.append(" nofreespy")
                    when {
                        room.autoPayTicket && room.autoPaySpy -> sb.append(" autopay")
                        room.autoPayTicket -> sb.append(" autopay:ticket")
                        room.autoPaySpy -> sb.append(" autopay:private")
                    }
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

/** How many favorite models are resolved to room names at once during a favorites import. */
private const val FAVORITES_LOOKUP_CONCURRENCY = 8

/** Quality assigned to rooms discovered through favorites; the user can change it later. */
private const val FAVORITES_DEFAULT_QUALITY = "highest"

/** Platform timeout for a favorites command: several accounts plus one lookup per unknown model. */
private const val FAVORITES_COMMAND_TIMEOUT_MS = 120_000L
