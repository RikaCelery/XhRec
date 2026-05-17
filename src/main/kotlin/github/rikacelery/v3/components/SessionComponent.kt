package github.rikacelery.v3.components

import github.rikacelery.utils.ClientManager
import github.rikacelery.utils.withRetry
import github.rikacelery.v3.api.ApiClient
import github.rikacelery.v3.core.*
import github.rikacelery.v3.data.*
import github.rikacelery.v3.events.*
import github.rikacelery.v3.m3u8.M3u8Parser
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.URLProtocol
import io.ktor.http.buildUrl
import io.ktor.http.encodedPath
import io.ktor.http.parameters
import kotlinx.coroutines.*
import java.time.Duration
import java.time.Instant
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

sealed interface SessionMsg
data class OnSessionEvent(val event: Any) : SessionMsg
data class DoStart(val roomId: Long, val roomName: String, val quality: String) : SessionMsg
data class DoStop(val roomId: Long) : SessionMsg
data class DoBreak(val roomId: Long) : SessionMsg
data class DoCutPointDone(val roomId: Long) : SessionMsg
data class HandleSessionCommand(val env: CommandEnvelope) : SessionMsg

enum class SessionState { Idle, Armed, Fetching, Recording, Closing }

data class RoomSession(
    val roomId: Long,
    var roomName: String,
    var quality: String,
    var state: SessionState = SessionState.Idle,
    var playlistUrl: String = "",
    var initUrl: String? = null,
    var token: String? = null,
    var segmentIndex: Int = 0,
    val circleCache: CircleCache = CircleCache(100),
    var startTime: Instant = Instant.now(),
    var totalBytes: Long = 0,
    var timeLimitMs: Long = 0,
    var sizeLimitBytes: Long = 0,
    var pollingJob: Job? = null
)

class CircleCache(private val capacity: Int) {
    private val set = LinkedHashSet<String>()
    fun add(url: String): Boolean {
        if (set.size >= capacity) {
            set.clear(); return true
        }
        return set.add(url)
    }

    fun clear() = set.clear()
}

class SessionComponent(
    private val dataChannel: DataChannel,
    private val downloader: DownloaderComponent,
    private val m3u8Parser: M3u8Parser,
    private val requestBus: RequestBus,
    private val apiClient: ApiClient,
    eventBus: EventBus,
    parentScope: CoroutineScope
) : Actor<SessionMsg>("SessionComponent", eventBus, parentScope) {

    private val sessions = ConcurrentHashMap<Long, RoomSession>()

    override suspend fun onStart(scope: CoroutineScope) {
        subscribe<RoomStatusChanged>(RoomStatusChanged::class)
        subscribe<LiveMessage>(LiveMessage::class)
        subscribe<QualitiesAvailable>(QualitiesAvailable::class)
        subscribe<CommandEnvelope>(CommandEnvelope::class)
    }

    override suspend fun wrapEvent(event: Any): SessionMsg? = when (event) {
        is RoomStatusChanged -> OnSessionEvent(event)
        is LiveMessage -> OnSessionEvent(event)
        is QualitiesAvailable -> OnSessionEvent(event)
        is CommandEnvelope -> HandleSessionCommand(event)
        else -> null
    }

    override suspend fun handle(msg: SessionMsg) {
        when (msg) {
            is DoStart -> startSession(msg.roomId, msg.roomName, msg.quality)
            is DoStop -> stopSession(msg.roomId)
            is DoBreak -> {
                val rs = sessions[msg.roomId] ?: return
                if (rs.state == SessionState.Recording) {
                    downloader.tell(
                        DoCutPoint(
                            CutPoint(
                                msg.roomId, rs.segmentIndex - 1, rs.roomName, Instant.now(), EndReason.SizeLimit
                            )
                        )
                    )
                    rs.startTime = Instant.now()
                    rs.totalBytes = 0
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

    private suspend fun startSession(roomId: Long, name: String, quality: String) {
        if (sessions.containsKey(roomId) && (sessions[roomId]!!.state == SessionState.Fetching ||
                    sessions[roomId]!!.state == SessionState.Recording)
        ) {
            return
        }
        logger.info("Session starting: roomId={}, name={}, quality={}", roomId, name, quality)
        val rs = RoomSession(roomId, name, quality)
        sessions[roomId] = rs
        rs.state = SessionState.Fetching
        rs.startTime = Instant.now()
        dataChannel.send(StreamStart(roomId, name, rs.startTime))
        rs.pollingJob = scope.launch { pollingLoop(rs) }
        rs.playlistUrl = buildPlaylistUrl(roomId, quality)
    }

    private suspend fun stopSession(roomId: Long) {
        val rs = sessions[roomId] ?: return
        logger.info("Session stopping: roomId={}, name={}, state={}", roomId, rs.roomName, rs.state)
        rs.state = SessionState.Closing
        rs.pollingJob?.cancel()
        downloader.tell(DoCutPoint(CutPoint(roomId, rs.segmentIndex, rs.roomName, Instant.now(), EndReason.UserStop)))
    }

    private suspend fun handleEvent(event: Any) {
        when (event) {
            is RoomStatusChanged -> {
                val rs = sessions[event.roomId] ?: return
                if (event.newStatus == "public" || event.newStatus == "groupShow") {
                    if (rs.state == SessionState.Armed) {
                        startSession(event.roomId, rs.roomName, rs.quality)
                    }
                } else if (event.newStatus == "offline" || event.newStatus == "private") {
                    if (rs.state == SessionState.Recording) {
                        rs.state = SessionState.Closing
                        rs.pollingJob?.cancel()
                        downloader.tell(
                            DoCutPoint(
                                CutPoint(
                                    event.roomId, rs.segmentIndex, rs.roomName, Instant.now(), EndReason.StreamEnd
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
                val newQuality = selectQuality(event.qualities, rs.quality)
                if (newQuality != rs.quality) {
                    rs.quality = newQuality
                    rs.playlistUrl = buildPlaylistUrl(event.roomId, newQuality)
                }
            }

            else -> {}
        }
    }

    private fun selectQuality(available: List<String>, requested: String): String {
        if (requested == "raw") return "raw"
        if (requested in available) return requested
        return available.lastOrNull { it <= requested } ?: available.firstOrNull() ?: requested
    }

    private fun buildPlaylistUrl(roomId: Long, quality: String, token: String? = null): String = buildUrl {
        protocol = URLProtocol.HTTPS
        host = "media-hls.doppiocdn.org"
        encodedPath = if (quality != "raw" && quality.isNotBlank()) "b-hls-%d/%d/%d_%s.m3u8".format(
            Random().nextInt(12, 13), roomId, roomId, quality
        ) else "b-hls-%d/%d/%d.m3u8".format(
            Random().nextInt(12, 13), roomId, roomId
        )
        parameters["psch"] = "v2"
        parameters["pkey"] = "Fq6m2TO2ZeBkRPm9"
        if (token != null) {
            parameters["aclAuth"] = token
        }
    }.toString()

    private suspend fun CoroutineScope.pollingLoop(rs: RoomSession) {
        while (isActive && (rs.state == SessionState.Fetching || rs.state == SessionState.Recording)) {
            delay(500)
            try {
                val client = ClientManager.getProxiedClient("m3u8_${rs.roomId}")
                val response = withRetry(3) { client.get(rs.playlistUrl) }
                val text = response.bodyAsText()

                val key = (requestBus.request<ConfigResponse>(GetDecryptKey("v2")).value as? String) ?: run {
                    logger.error("No decrypt key for room ${rs.roomId}")
                    delay(5000)
                    continue
                }
                val parsed = m3u8Parser.parse(text, key)
                requireNotNull(parsed.initUrl)

                if (parsed.initUrl != rs.initUrl) {
                    downloader.tell(
                        DoCutPoint(
                            CutPoint(
                                rs.roomId, rs.segmentIndex - 1, rs.roomName, Instant.now(), EndReason.NewInit
                            )
                        )
                    )
                    rs.segmentIndex = 0
                    rs.circleCache.clear()
                    rs.initUrl = parsed.initUrl
                }
                if (rs.circleCache.add(parsed.initUrl)) {
                    downloader.tell(DoDownload(Download(rs.roomId, listOf(Segment(parsed.initUrl,-1)), rs.segmentIndex)))
                    rs.segmentIndex +=1
                }
                val unseen = parsed.segments.filter { rs.circleCache.add(it.url) }
                if (unseen.isNotEmpty()) {
                    eventBus.publish(NewSegments(rs.roomId, unseen))
                    downloader.tell(DoDownload(Download(rs.roomId, unseen, rs.segmentIndex)))
                    rs.segmentIndex += unseen.size
                }

                val limit = if (rs.timeLimitMs > 0) rs.timeLimitMs else Long.MAX_VALUE
                val elapsed = Duration.between(rs.startTime, Instant.now()).toMillis()
                if (elapsed >= limit || (rs.sizeLimitBytes > 0 && rs.totalBytes >= rs.sizeLimitBytes)) {
                    downloader.tell(
                        DoCutPoint(
                            CutPoint(
                                rs.roomId, rs.segmentIndex - 1, rs.roomName, Instant.now(), EndReason.SizeLimit
                            )
                        )
                    )
                    rs.startTime = Instant.now()
                    rs.totalBytes = 0
                }

                if (rs.state == SessionState.Fetching) rs.state = SessionState.Recording

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Polling error room ${rs.roomId}: ${e.message}")
                eventBus.publish(DownloadError(rs.roomId, null, null, e.message ?: "poll error"))
            }
        }
    }

    private suspend fun onCutPointDone(roomId: Long) {
        val rs = sessions[roomId] ?: return
        rs.pollingJob = scope.launch { pollingLoop(rs) }
    }
}
