package github.rikacelery.v3.components

import github.rikacelery.v3.api.ApiClient
import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.DataChannel
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.data.StreamEvent
import github.rikacelery.v3.data.StreamStart
import github.rikacelery.v3.data.User
import github.rikacelery.v3.events.*
import github.rikacelery.v3.m3u8.M3u8Parser
import github.rikacelery.v3.m3u8.MasterPlaylist
import github.rikacelery.v3.utils.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

sealed interface SessionMsg
data class OnSessionEvent(val event: Any) : SessionMsg
data class DoStart(val roomId: Long, val roomName: String, val quality: String, val pkey: String = "") : SessionMsg
data class DoStop(val roomId: Long) : SessionMsg
data class DoBreak(val roomId: Long, val reason: EndReason) : SessionMsg
data class DoCutPointDone(val roomId: Long) : SessionMsg
data class HandleSessionCommand(val env: CommandEnvelope) : SessionMsg

enum class SessionState { Idle, Armed, Fetching, Recording, Closing }

data class RoomSession(
    val roomId: Long,
    var roomName: String,
    var quality: String,
    var targetquality: String,
    var state: SessionState = SessionState.Idle,
    var playlistUrl: String = "",
    var initUrl: String? = null,
    var token: String? = null,
    var pkey: String = "",
    var segmentIndex: Int = 0,
    var generation: Int = 0,
    val circleCache: CircleCache = CircleCache(100),
    var startTime: Instant = Instant.now(),
    var totalBytes: Long = 0,
    var timeLimit: Duration = Duration.INFINITE,
    var sizeLimitBytes: Long = 0,
    var pollingJob: Job? = null,
    var masterPlaylist: MasterPlaylist? = null
)

class CircleCache(private val capacity: Int) {
    private val set = LinkedHashSet<String>()
    fun add(url: String): Boolean {
        if (set.size >= capacity) {
            set.clear(); return true
        }
        return set.add(url)
    }

    fun remove(url: String) = set.remove(url)
    fun clear() = set.clear()
}

class SessionComponent(
    private val dataChannel: DataChannel,
    private val downloader: DownloaderComponent,
    private val m3u8Parser: M3u8Parser,
    private val requestBus: RequestBus,
    private val apiClient: ApiClient,
    private val streamAuthKey: String,
    eventBus: EventBus,
    parentScope: CoroutineScope
) : Actor<SessionMsg>("SessionComponent", eventBus, parentScope) {

    private val sessions = ConcurrentHashMap<Long, RoomSession>()
    private val lastBlockReason = ConcurrentHashMap<Long, String>()
    private val decryptKeyCache = ConcurrentHashMap<String, String>()

    override suspend fun onStart(scope: CoroutineScope) {
        subscribe<RoomStatusChanged>(RoomStatusChanged::class)
        subscribe<LiveMessage>(LiveMessage::class)
        subscribe<QualitiesAvailable>(QualitiesAvailable::class)
        subscribe<SegmentDownloaded>(SegmentDownloaded::class)
        subscribe<CommandEnvelope>(CommandEnvelope::class)
        subscribe<QualityChangeRequested>(QualityChangeRequested::class)
        subscribe<RoomTimeLimitChanged>(RoomTimeLimitChanged::class)
        subscribe<RoomSizeLimitChanged>(RoomSizeLimitChanged::class)

        eventBus.subscribe(scope, PersistConfig::class) {
            decryptKeyCache.clear()
        }

        scope.launch {
            while (isActive) {
                delay(30.seconds)
                pollQualities()
                cleanStaleSessions()
            }
        }
    }

    override suspend fun wrapEvent(event: Any): SessionMsg? = when (event) {
        is RoomStatusChanged -> OnSessionEvent(event)
        is LiveMessage -> OnSessionEvent(event)
        is QualitiesAvailable -> OnSessionEvent(event)
        is SegmentDownloaded -> OnSessionEvent(event)
        is QualityChangeRequested -> OnSessionEvent(event)
        is RoomTimeLimitChanged -> OnSessionEvent(event)
        is RoomSizeLimitChanged -> OnSessionEvent(event)
        is CommandEnvelope -> HandleSessionCommand(event)
        else -> null
    }

    override suspend fun handle(msg: SessionMsg) {
        when (msg) {
            is DoStart -> startSession(msg.roomId, msg.roomName, msg.quality, msg.pkey)
            is DoStop -> stopSession(msg.roomId)
            is DoBreak -> {
                val rs = sessions[msg.roomId] ?: return
                when (rs.state) {
                    SessionState.Recording -> {
                        if (msg.reason == EndReason.UserStop) {
                            stopSession(msg.roomId)
                        } else {
                            downloader.tell(
                                DoCutPoint(
                                    CutPoint(
                                        msg.roomId, rs.segmentIndex - 1, rs.roomName, Instant.now(), msg.reason, rs.quality
                                    )
                                )
                            )
                            rs.startTime = Instant.now()
                            rs.generation += 1
                            rs.totalBytes = 0
                            rs.segmentIndex = 0
                            rs.initUrl?.let { rs.circleCache.remove(it) }
                        }
                    }

                    SessionState.Fetching -> {
                        stopSession(msg.roomId)
                    }

                    else -> {} // Armed, Closing, Idle — nothing to do
                }
            }

            is OnSessionEvent -> handleEvent(msg.event)
            is DoCutPointDone -> onCutPointDone(msg.roomId)
            is HandleSessionCommand -> {
                handleCommand(msg.env)
            }
        }
    }

    private suspend fun handleCommand(env: CommandEnvelope) {
        val ack = when (env.command) {
            is GetSessions -> sessions.values.map { it.copy() }
            else -> return
        }
        eventBus.publish(CommandAck(env.id, ack))
    }

    private suspend fun startSession(
        roomId: Long, name: String, quality: String, pkey: String = "", reconfigure: Boolean = true
    ) {
        SensitiveStringRegistry.mask(name)
        val existing = sessions[roomId]
        if (existing != null) {
            val blocked = when (existing.state) {
                SessionState.Fetching, SessionState.Recording -> true
                SessionState.Closing -> existing.pollingJob?.isCompleted != true
                else -> false
            }
            if (blocked) return
        }
        // Register session synchronously so DoStop/DoBreak can find it
        val rs = RoomSession(roomId, name, quality, quality, pkey = pkey.ifBlank { streamAuthKey })
        sessions[roomId] = rs
        rs.state = SessionState.Fetching
        rs.startTime = Instant.now()

        scope.launch {
            if (reconfigure) {
                val token = configureSession(roomId, name) ?: run {
                    logger.info("[{}] Session not started: configure returned false", name)
                    sessions.remove(roomId)
                    delay(5.seconds)
                    requestBus.request<OkResponse>(RefreshRoomCmd(roomId))
                    return@launch
                }
                rs.token = token.takeIf { it.isNotEmpty() }
                lastBlockReason.remove(roomId)
            }
            val config = requestBus.request<RoomConfigResponse>(GetRoomConfig(roomId))
            rs.timeLimit = config.timeLimit
            rs.sizeLimitBytes = config.sizeLimitBytes
            try {
                rs.masterPlaylist = fetchAndCacheMasterPlaylist(rs)
                val availableNames = rs.masterPlaylist!!.variants.map { it.name }
                val selected = selectQuality(availableNames, rs.quality)
                rs.quality = selected
                rs.targetquality = selected
                rs.playlistUrl = resolveVariantUrl(rs)
            } catch (e: Exception) {
                logger.warn("[{}] Master playlist failed, falling back to API: {}", rs.roomName, e.message)
                val qualities = apiClient.roomQualities(rs.roomName)
                val selected = selectQuality(qualities, rs.quality)
                val rawQuality = qualities.firstOrNull()
                val useRaw = selected == rawQuality
                rs.quality = selected
                rs.targetquality = selected
                rs.playlistUrl = buildFallbackPlaylistUrl(rs, useRaw)
            }
            logger.info("Session starting: roomId={}, name={}, quality={}", roomId, name, rs.quality)
            dataChannel.send(StreamStart(roomId, name, rs.startTime, rs.quality))
            rs.pollingJob = launch { pollingLoop(rs) }
            eventBus.publish(RecordingStarted(roomId, rs.quality))
        }
    }

    private suspend fun stopSession(roomId: Long) {
        val rs = sessions[roomId] ?: return
        logger.info("Session stopping: roomId={}, name={}, state={}", roomId, rs.roomName, rs.state)
        rs.state = SessionState.Closing
        rs.pollingJob?.cancel()
        downloader.tell(DoCutPoint(CutPoint(roomId, rs.segmentIndex, rs.roomName, Instant.now(), EndReason.UserStop, rs.quality)))
    }

    private suspend fun handleEvent(event: Any) {
        when (event) {
            is RoomStatusChanged -> {
                val rs = sessions[event.roomId] ?: return
                if (event.newStatus == "public" || event.newStatus == "groupShow") {
                    if (rs.state == SessionState.Armed) {
                        if (event.newStatus == "groupShow") {
                            // SchedulerComponent handles groupShow via DoStart→startSession→configureSession
                            return
                        }
                        startSession(event.roomId, rs.roomName, rs.quality, rs.pkey)
                    }

                } else if (event.newStatus == "offline" || event.newStatus == "private") {
                    if (rs.state == SessionState.Recording) {
                        rs.state = SessionState.Closing
                        rs.pollingJob?.cancel()
                        rs.generation += 1
                        downloader.tell(
                            DoCutPoint(
                                CutPoint(
                                    event.roomId, rs.segmentIndex, rs.roomName, Instant.now(), EndReason.StreamEnd, rs.quality
                                )
                            )
                        )
                    }
                }
            }

            is LiveMessage -> {
                dataChannel.send(StreamEvent(event.roomId, Instant.now(), event.body.toString()))
            }

            is QualitiesAvailable -> {
                val rs = sessions[event.roomId] ?: return
                if (rs.state != SessionState.Recording) return
                val newQuality = selectQuality(event.qualities, rs.targetquality)
                if (newQuality != rs.quality) {
                    logger.info(
                        "Quality switched for {}: {} -> {} (available: {})",
                        rs.roomName,
                        rs.quality,
                        newQuality,
                        event.qualities
                    )
                    rs.quality = newQuality
                    rs.playlistUrl = resolveVariantUrl(rs)
                } else {
//                    logger.debug(
//                        "Quality unchanged for {}: {} (available: {})", rs.roomName, rs.quality, event.qualities
//                    )
                }
            }

            is QualityChangeRequested -> {
                val rs = sessions[event.roomId] ?: return
                logger.info("Quality change requested for {}: {} -> {}", rs.roomName, rs.quality, event.newQuality)
                rs.targetquality = event.newQuality
                if (rs.state == SessionState.Recording || rs.state == SessionState.Fetching) {
                    pollQualityForRoom(rs)
                }
            }

            is RoomTimeLimitChanged -> {
                val rs = sessions[event.roomId] ?: return
                logger.info("Time limit changed for {}: {} -> {}", rs.roomName, rs.timeLimit, event.limit)
                rs.timeLimit = event.limit
            }

            is RoomSizeLimitChanged -> {
                val rs = sessions[event.roomId] ?: return
                logger.info("Size limit changed for {}: {} -> {}", rs.roomName, rs.sizeLimitBytes, event.limitBytes)
                rs.sizeLimitBytes = event.limitBytes
            }

            is SegmentDownloaded -> {
                val rs = sessions[event.roomId] ?: return
                if (event.generation != rs.generation) return
                rs.totalBytes += event.bytes
            }

            else -> {}
        }
    }

    private fun selectQuality(available: List<String>, requested: String): String {
        if (requested == "highest") return "highest"
        val clean = available.filterNot { it.contains("blurred") }
        if (clean.isEmpty()) return requested
        if (requested in available) return requested
        val reqParts = requested.split("p").filterNot(String::isEmpty)
        val final = clean.minByOrNull { q ->
            val qParts = q.split("p").filterNot(String::isEmpty)
            if (reqParts.size == 2) {
                abs((qParts[0].toIntOrNull() ?: 0) - (reqParts[0].toIntOrNull() ?: 0)) + abs(
                    (reqParts[1].toIntOrNull() ?: 30) - (qParts.getOrElse(1) { "30" }.toIntOrNull() ?: 30)
                )
            } else {
                abs((qParts[0].toIntOrNull() ?: 0) - (reqParts[0].toIntOrNull() ?: 0))
            }
        } ?: requested
        return final
    }

    private suspend fun pollQualities() {
        for (rs in sessions.values) {
            if (rs.state != SessionState.Recording) continue
            pollQualityForRoom(rs)
        }
    }

    private suspend fun pollQualityForRoom(rs: RoomSession) {
        try {
            val master = fetchAndCacheMasterPlaylist(rs)
            val availableNames = master.variants.map { it.name }
            if (availableNames.isNotEmpty()) {
                eventBus.publish(QualitiesAvailable(rs.roomId, availableNames))
            }
        } catch (e: Exception) {
            logger.warn("[{}] Master playlist poll failed, falling back to API: {}", rs.roomName, e.message)
            try {
                val qualities = apiClient.roomQualities(rs.roomName)
                if (qualities.isNotEmpty()) {
                    eventBus.publish(QualitiesAvailable(rs.roomId, qualities))
                }
            } catch (_: Exception) {
                // both master and API failed; ignore
            }
        }
    }

    private fun cleanStaleSessions() {
        val toRemove = sessions.filter { (_, rs) ->
            rs.state == SessionState.Idle || (rs.state == SessionState.Closing && rs.pollingJob?.isCompleted == true)
        }
        for (roomId in toRemove.keys) {
            sessions.remove(roomId)
            logger.debug("Cleaned up stale session for room {}", roomId)
        }
    }

    /** Returns model token for groupShow, empty string for public, null if can't record */
    private suspend fun configureSession(roomId: Long, roomName: String): String? {
        try {
            val info = apiClient.roomFetchBroadcastInfo(roomName)
            val status = info.PathSingle("item.status").asString()
            when (status) {
                "public" -> return ""
                "groupShow" -> {
                    val config = requestBus.request<RoomConfigResponse>(GetRoomConfig(roomId))
                    sessions[roomId]?.let { rs ->
                        rs.timeLimit = config.timeLimit
                        rs.sizeLimitBytes = config.sizeLimitBytes
                    }
                    if (!config.autoPay) {
                        val reason = "autopay disabled"
                        if (lastBlockReason.put(roomId, reason) != reason)
                            logger.warn("[{}] Room not enable autopay", roomName)
                        return null
                    }
                    val camInfo = apiClient.roomFetchCamInfo(roomName, "")
                    val price = camInfo.PathSingle("user.user.ticketRate").asInt()
                    val users = requestBus.request<List<User>>(GetValidPaymentAccount(price.toLong()))
                    val u = users.firstOrNull()
                    if (u == null) {
                        val reason = "insufficient balance"
                        if (lastBlockReason.put(roomId, reason) != reason)
                            logger.warn("[{}] No account to pay. price={}", roomName, price)
                        return null
                    }
                    var token = apiClient.roomFetchModelToken(roomName, u)
                    if (token == null) {
                        apiClient.roomRequestGroupShow(roomId, u)
                        requestBus.request<OkResponse>(DeductCoins(u.userId, price.toLong()))
                        delay(1.seconds)
                        token = apiClient.roomFetchModelToken(roomName, u)
                    }
                    if (token == null) {
                        logger.warn("[{}] Failed to get model token", roomName)
                        return null
                    }
                    return token
                }

                else -> {
                    logger.trace("[{}] -> false, status={}", roomName, status)
                    return null
                }
            }
        } catch (e: ClientRequestException) {
            logger.warn("Room configure failed for {}: {}", roomName, e.message)
        } catch (e: Exception) {
            logger.warn("Room configure failed for {}: {}", roomName, e.message)
        }

        return null
    }

    private fun buildMasterUrl(roomId: Long): String =
        "https://edge-hls.doppiocdn.org/hls/$roomId/master/${roomId}_auto.m3u8"

    private fun buildFallbackPlaylistUrl(rs: RoomSession, useRaw: Boolean = false): String {
        val token = rs.token
        return buildUrl {
            protocol = URLProtocol.HTTPS
            host = "media-hls.doppiocdn.org"
            encodedPath = if (useRaw) "b-hls-%d/%d/%d.m3u8".format(
                22, rs.roomId, rs.roomId
            ) else "b-hls-%d/%d/%d_%s.m3u8".format(
                22, rs.roomId, rs.roomId, rs.quality
            )
            parameters["psch"] = "v2"
            parameters["pkey"] = rs.pkey
            if (token != null) {
                parameters["aclAuth"] = token
            }
        }.toString()
    }

    private suspend fun fetchAndCacheMasterPlaylist(rs: RoomSession): MasterPlaylist {
        val client = ClientManager.getProxiedClient("master_${rs.roomId}")
        val url = buildMasterUrl(rs.roomId)
        val response = withRetry(3) { client.get(url) }
        val text = response.bodyAsText()
        val master = m3u8Parser.parseMaster(text)
        rs.masterPlaylist = master
        val keyIds = master.pschKeys.map { it.substringAfter(":") }
        val match = requestBus.request<DecryptKeyMatch>(MatchDecryptKeys(keyIds))
        require(match.decryptKey.isNotEmpty()) {
            "[${rs.roomName}] No PSCH key from master playlist matched in persistedDecryptKeys. keys=$keyIds"
        }
        rs.pkey = match.keyName
        return master
    }

    private fun resolveVariantUrl(rs: RoomSession): String {
        val mp = rs.masterPlaylist!!
        val variant = if (rs.quality == "highest") {
            mp.variants.maxByOrNull { it.bandwidth }
        } else {
            val availableNames = mp.variants.map { it.name }
            val matched = selectQuality(availableNames, rs.quality)
            mp.variants.find { it.name == matched }
        }
        val baseUrl = variant?.url ?: mp.variants.maxByOrNull { it.bandwidth }!!.url
        return buildUrl {
            takeFrom(baseUrl)
            parameters["psch"] = "v2"
            parameters["pkey"] = rs.pkey
        }.toString()
    }

    private fun createDiscontinuityCounter(): suspend (List<Int>) -> Int {
        var lastMax: Int? = null
        var totalGaps = 0
        val lock = Mutex()
        return counter@{ numbers: List<Int> ->
            lock.withLock {
                val filtered = numbers.filter { it != 0 }
                if (filtered.isEmpty()) return@counter totalGaps

                val currentMin = filtered.first()
                val currentMax = filtered.last()

                if (lastMax != null) {
                    val gap = currentMin - lastMax!! - 1
                    if (gap > 0) {
                        totalGaps += gap
                    }
                }

                lastMax = currentMax
                totalGaps
            }
        }
    }

    private suspend fun CoroutineScope.pollingLoop(rs: RoomSession) {
        try {
            val counter = createDiscontinuityCounter()
            var firstPoll = true
            while (isActive && (rs.state == SessionState.Fetching || rs.state == SessionState.Recording)) {
                val pollDelay = if (firstPoll) {
                    3.seconds + Random.nextLong(-500, 500).milliseconds
                } else {
                    3.seconds
                }
                firstPoll = false
                delay(pollDelay)
                try {
                    val client = ClientManager.getProxiedClient("m3u8_${rs.roomId}")
                    val fetchStart = System.currentTimeMillis()
                    val response = withRetry(3) {
                        client.get(rs.playlistUrl)
                    }
                    val fetchLatency = System.currentTimeMillis() - fetchStart
                    val text = response.bodyAsText()

                    val key = decryptKeyCache[rs.pkey] ?: run {
                        val result = (requestBus.request<ConfigResponse>(GetDecryptKey(rs.pkey)).value as? String)
                        if (result != null) decryptKeyCache[rs.pkey] = result
                        result
                    } ?: run {
                        logger.error("No decrypt key for room ${rs.roomId}")
                        delay(5.seconds)
                        continue
                    }
                    val parsed = m3u8Parser.parse(text, key)
                    val maxSegId = parsed.segments.maxOfOrNull { m3u8Parser.segmentIDFromUrl(it.url) ?: 0 } ?: 0
                    eventBus.publish(PlaylistRefreshed(rs.roomId, fetchLatency, maxSegId))
                    requireNotNull(parsed.initUrl)

                    if (parsed.initUrl != rs.initUrl) {
                        downloader.tell(
                            DoCutPoint(
                                CutPoint(
                                    rs.roomId, rs.segmentIndex - 1, rs.roomName, Instant.now(), EndReason.NewInit, rs.quality
                                )
                            )
                        )
                        rs.generation += 1
                        rs.segmentIndex = 0
                        rs.circleCache.clear()
                        rs.initUrl = parsed.initUrl
                    }
                    if (rs.circleCache.add(parsed.initUrl)) {
                        downloader.tell(
                            DoDownload(
                                Download(
                                    rs.roomId, listOf(Segment(parsed.initUrl, -1)), rs.segmentIndex, rs.generation
                                )
                            )
                        )
                        rs.segmentIndex += 1
                    }
                    val unseen = parsed.segments.filter { rs.circleCache.add(it.url) }
                    if (unseen.isNotEmpty()) {
                        val gap = counter(unseen.map {
                            m3u8Parser.segmentIDFromUrl(it.url)
                        }.filterNotNull())
                        if (gap > 0) {
                            eventBus.publish(SegmentGapDetected(rs.roomId, gap))
                        }
                        eventBus.publish(NewSegments(rs.roomId, unseen))
                        downloader.tell(DoDownload(Download(rs.roomId, unseen, rs.segmentIndex, rs.generation)))
                        rs.segmentIndex += unseen.size
                    }

                    val limitMs =
                        if (rs.timeLimit != Duration.INFINITE) rs.timeLimit.inWholeMilliseconds else Long.MAX_VALUE
                    val elapsed = java.time.Duration.between(rs.startTime, Instant.now()).toMillis()
                    if (elapsed >= limitMs || (rs.sizeLimitBytes > 0 && rs.totalBytes >= rs.sizeLimitBytes)) {
                        val reason = if (elapsed >= limitMs) EndReason.TimeLimit else EndReason.SizeLimit
                        downloader.tell(
                            DoCutPoint(
                                CutPoint(
                                    rs.roomId, rs.segmentIndex - 1, rs.roomName, Instant.now(), reason, rs.quality
                                )
                            )
                        )
                        rs.startTime = Instant.now()
                        rs.generation += 1
                        rs.totalBytes = 0
                        rs.segmentIndex = 0
                        rs.circleCache.remove(parsed.initUrl)
                    }

                    if (rs.state == SessionState.Fetching) {
                        synchronized(rs) {
                            if (rs.state == SessionState.Fetching) rs.state = SessionState.Recording
                        }
                    }

                } catch (e: CancellationException) {
                    if (e is TimeoutCancellationException) {
                        logger.warn("[{}] Refresh list timeout", rs.roomName)
                        if (configureSession(rs.roomId, rs.roomName) == null) {
                            logger.info("[STOP] [{}] Room off or non-public after timeout", rs.roomName)
                            rs.state = SessionState.Closing
                            downloader.tell(
                                DoCutPoint(
                                    CutPoint(
                                        rs.roomId,
                                        rs.segmentIndex - 1,
                                        rs.roomName,
                                        Instant.now(),
                                        EndReason.UserStop, rs.quality
                                    )
                                )
                            )
                            break
                        }
                        rs.playlistUrl = resolveVariantUrl(rs)
                        continue
                    }
                    throw e
                } catch (e: ClientRequestException) {
                    if (e.response.status == HttpStatusCode.Forbidden) {
                        val token = configureSession(rs.roomId, rs.roomName)
                        if (token != null) {
                            rs.token = token.takeIf { it.isNotEmpty() }
                            rs.playlistUrl = resolveVariantUrl(rs)
                            continue
                        }
                        logger.info("[STOP] [{}] Room off or non-public (403)", rs.roomName)
                        rs.state = SessionState.Closing
                        downloader.tell(
                            DoCutPoint(
                                CutPoint(
                                    rs.roomId,
                                    rs.segmentIndex - 1,
                                    rs.roomName,
                                    Instant.now(),
                                    EndReason.UserStop
                                )
                            )
                        )
                        break
                    } else if (e.response.status == HttpStatusCode.NotFound) {
                        logger.info("[STOP] [{}] Stream url returns 404", rs.roomName)
                        rs.state = SessionState.Closing
                        downloader.tell(
                            DoCutPoint(
                                CutPoint(
                                    rs.roomId,
                                    rs.segmentIndex - 1,
                                    rs.roomName,
                                    Instant.now(),
                                    EndReason.UserStop
                                )
                            )
                        )
                        break
                    } else {
                        logger.error("Polling error room ${rs.roomId}: ${e.message}")
                        rs.state = SessionState.Closing
                        downloader.tell(
                            DoCutPoint(
                                CutPoint(
                                    rs.roomId,
                                    rs.segmentIndex - 1,
                                    rs.roomName,
                                    Instant.now(),
                                    EndReason.UserStop
                                )
                            )
                        )
                        break
                    }
                } catch (e: Exception) {
                    logger.error("[{}] Unexpected error in polling", rs.roomName, e)
                    if (configureSession(rs.roomId, rs.roomName) == null) {
                        logger.info("[STOP] [{}] Room off or non-public", rs.roomName)
                    }
                    try {
                        rs.masterPlaylist = fetchAndCacheMasterPlaylist(rs)
                        rs.playlistUrl = resolveVariantUrl(rs)
                    } catch (_: Exception) {
                        continue
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                eventBus.publish(RecordingStopped(rs.roomId))
            }
        }
    }

    private fun onCutPointDone(roomId: Long) {
        val rs = sessions[roomId] ?: return
        rs.pollingJob = scope.launch { pollingLoop(rs) }
    }
}
