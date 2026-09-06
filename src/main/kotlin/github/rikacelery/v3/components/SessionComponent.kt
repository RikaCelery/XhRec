package github.rikacelery.v3.components

import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.DataChannel
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.data.StreamStart
import github.rikacelery.v3.events.*
import github.rikacelery.v3.fsm.KEEP
import github.rikacelery.v3.fsm.LoopTimer
import github.rikacelery.v3.fsm.StateMachine
import github.rikacelery.v3.fsm.buildFsm
import github.rikacelery.v3.m3u8.M3u8Parser
import github.rikacelery.v3.m3u8.ParsedPlaylist
import github.rikacelery.v3.utils.ClientManager
import github.rikacelery.v3.utils.withRetry
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import java.time.Duration as JavaDuration

// ============================================================
// Session FSM definitions
// ============================================================

enum class RecordingState { Idle, Recording, Closing }

/** Session-state view kept for HTTP API compatibility */
enum class SessionState { Idle, Armed, Fetching, Recording, Closing }

data class RoomSession(
    val roomId: Long,
    val roomName: String,
    val quality: String,
    val state: SessionState,
    val startTime: Instant
)

enum class RecordingEvent {
    StartRecording, StopRecording,
    PlaylistFetched, PlaylistFetchFailed, PlaylistUnusable,
    PlaylistRetryExhausted,
    SegmentDownloaded, CutPointDone, LimitReached, InitChanged,
}

data class RecordingDriveData(
    val playlist: ParsedPlaylist? = null,
    val failReason: String? = null,
    val segBytes: Long? = null,
    val segGeneration: Long? = null,
    val stopReason: EndReason? = null,
)

// ============================================================
// Messages
// ============================================================

sealed interface SessionMsg

data class StartRecording(
    val roomId: Long,
    val roomName: String,
    val playlistUrl: String,
    val pkey: String,
    val quality: String,
    val startIndex: Long?,
    val timeLimit: Duration,
    val sizeLimitBytes: Long,
) : SessionMsg

data class StopRecording(val roomId: Long, val reason: EndReason) : SessionMsg

/** Async-result envelope fed back into the mailbox */
data class SessionSignal(val roomId: Long, val event: RecordingEvent, val data: RecordingDriveData?) : SessionMsg

/** Wrapper for bus events */
data class SessionBus(val event: Any) : SessionMsg

data class SessionHandleCommand(val env: CommandEnvelope) : SessionMsg

// ============================================================
// Entry
// ============================================================

private val sessionLogger = LoggerFactory.getLogger("v3.SessionEntry")

class CircleCache(private val capacity: Int) {
    private val set = LinkedHashSet<String>()
    @Synchronized fun add(url: String): Boolean {
        if (set.size >= capacity) {
            set.clear()
            return true
        }
        return set.add(url)
    }
    @Synchronized fun remove(url: String) = set.remove(url)
    @Synchronized fun clear() = set.clear()
}

class SessionEntry(
    val roomId: Long,
    var roomName: String,
    internal val component: SessionComponent
) {
    // —— Preconfigured fields from StartRecording ——
    var playlistUrl: String = ""
    var pkey: String = ""
    var quality: String = ""
    var startIndex: Long? = null
    var timeLimit: Duration = Duration.INFINITE
    var sizeLimitBytes: Long = 0L
    var generation: Long = SecureRandom().nextLong()

    // —— Runtime state ——
    var lastSegmentId: Long? = null
    var lastInitUrl: String? = null
    var segmentIndex: Int = 0
    var totalBytes: Long = 0L
    var startTime: Instant = Instant.now()
    var retryCount: Int = 0
    val circleCache = CircleCache(100)

    // —— scope / loop timer ——
    val scope = CoroutineScope(
        component.ioScope.coroutineContext + SupervisorJob(component.ioScope.coroutineContext[Job])
    )
    val playlistLoop = LoopTimer<Unit>(scope)

    // —— fsm ——
    val fsm: StateMachine<RecordingState, RecordingEvent, RecordingDriveData, SessionEntry> =
        buildSessionFsm(this)

    fun launch(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sessionLogger.error("Session entry effect failed roomId={}", roomId, e)
            }
        }
    }

    internal fun cutFile(reason: EndReason) {
        playlistLoop.cancel()
        launch {
            component.downloader.tell(
                DoCutPoint(CutPoint(roomId, segmentIndex - 1, roomName, Instant.now(), reason, quality, generation))
            )
        }
    }

    internal fun onSegment(d: RecordingDriveData?) {
        val gen = d?.segGeneration ?: return
        if (gen != generation) return
        totalBytes += d.segBytes ?: 0L
        if (sizeLimitBytes > 0 && totalBytes >= sizeLimitBytes) {
            launch {
                component.tell(
                    SessionSignal(roomId, RecordingEvent.LimitReached, RecordingDriveData(stopReason = EndReason.SizeLimit))
                )
            }
        }
    }

    internal fun computeUnseen(parsed: ParsedPlaylist): List<Segment> {
        val unseen = mutableListOf<Segment>()
        parsed.initUrl?.let { init ->
            if (circleCache.add(init)) {
                unseen.add(Segment(init, -1))
            }
        }
        for (seg in parsed.segments) {
            val segId = component.m3u8Parser.segmentIDFromUrl(seg.url)?.toLong()
            if (segId != null) {
                val threshold = lastSegmentId
                if (threshold != null && segId <= threshold) continue
            }
            if (circleCache.add(seg.url)) {
                unseen.add(seg)
                if (segId != null) lastSegmentId = segId
            }
        }
        return unseen
    }

    internal suspend fun fetchPlaylistSignal(): SessionMsg {
        return try {
            withTimeout(10.seconds) {
                val client = ClientManager.getProxiedClient("m3u8_$roomId")
                val response = withRetry(3) { client.get(playlistUrl) }
                val text = response.bodyAsText()
                val key = (component.requestBus.request<ConfigResponse>(GetDecryptKey(pkey)).value as? String)
                    ?: return@withTimeout SessionSignal(
                        roomId, RecordingEvent.PlaylistFetchFailed,
                        RecordingDriveData(failReason = "no decrypt key")
                    )
                val parsed = component.m3u8Parser.parse(text, key)
                SessionSignal(roomId, RecordingEvent.PlaylistFetched, RecordingDriveData(playlist = parsed))
            }
        } catch (e: TimeoutCancellationException) {
            SessionSignal(roomId, RecordingEvent.PlaylistFetchFailed, RecordingDriveData(failReason = "timeout"))
        } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.NotFound || e.response.status == HttpStatusCode.Forbidden) {
                // playlist is no longer usable: end the session, the scheduler will reconfigure
                SessionSignal(roomId, RecordingEvent.PlaylistUnusable, RecordingDriveData(failReason = e.response.status.toString()))
            } else {
                SessionSignal(roomId, RecordingEvent.PlaylistFetchFailed, RecordingDriveData(failReason = e.message))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SessionSignal(roomId, RecordingEvent.PlaylistFetchFailed, RecordingDriveData(failReason = e.message))
        }
    }
}

// ============================================================
// Session FSM matrix
// ============================================================

private fun buildSessionFsm(ctx: SessionEntry) =
    buildFsm<RecordingState, RecordingEvent, RecordingDriveData, SessionEntry>(ctx) {

        initial(RecordingState.Idle)

        state(RecordingState.Idle) {
            on(RecordingEvent.StartRecording) to RecordingState.Recording action {
                startTime = Instant.now()
                segmentIndex = 0
                totalBytes = 0L
                retryCount = 0
                circleCache.clear()   // a new file must re-download the init; media segments are skipped via the lastSegmentId threshold
                launch { component.dataChannel.send(StreamStart(roomId, roomName, startTime, quality)) }
                playlistLoop.start(3.seconds) { component.tell(fetchPlaylistSignal()) }
            }
            on(RecordingEvent.StopRecording) to KEEP
            on(RecordingEvent.SegmentDownloaded) to KEEP
            on(RecordingEvent.CutPointDone) to KEEP
        }

        state(RecordingState.Recording) {
            on(RecordingEvent.PlaylistFetched) to KEEP action { d ->
                retryCount = 0
                val parsed = d?.playlist ?: return@action
                val initUrl = parsed.initUrl
                if (lastInitUrl != null && initUrl != null && lastInitUrl != initUrl) {
                    launch { component.tell(SessionSignal(roomId, RecordingEvent.InitChanged, null)) }
                    return@action
                }
                if (initUrl != null) lastInitUrl = initUrl
                val unseen = computeUnseen(parsed)
                segmentIndex += unseen.size
                if (unseen.isNotEmpty()) {
                    launch { component.downloader.tell(DoDownload(Download(roomId, unseen, segmentIndex, generation))) }
                }
                val elapsedMs = JavaDuration.between(startTime, Instant.now()).toMillis()
                if (timeLimit != Duration.INFINITE && elapsedMs >= timeLimit.inWholeMilliseconds) {
                    launch {
                        component.tell(
                            SessionSignal(roomId, RecordingEvent.LimitReached, RecordingDriveData(stopReason = EndReason.TimeLimit))
                        )
                    }
                }
            }
            on(RecordingEvent.PlaylistFetchFailed) to KEEP action { d ->
                if (++retryCount >= MAX_PLAYLIST_RETRY) {
                    launch {
                        component.tell(
                            SessionSignal(roomId, RecordingEvent.PlaylistRetryExhausted, RecordingDriveData(failReason = d?.failReason))
                        )
                    }
                }
            }
            on(RecordingEvent.SegmentDownloaded) to KEEP action { d -> onSegment(d) }
            on(RecordingEvent.StopRecording) to RecordingState.Closing action { d ->
                cutFile(d?.stopReason ?: EndReason.UserStop)
            }
            on(RecordingEvent.LimitReached) to RecordingState.Closing action { d ->
                cutFile(d?.stopReason ?: EndReason.SizeLimit)
            }
            on(RecordingEvent.InitChanged) to RecordingState.Closing action {
                cutFile(EndReason.NewInit)
            }
            on(RecordingEvent.PlaylistUnusable) to RecordingState.Closing action {
                cutFile(EndReason.StreamEnd)
            }
            on(RecordingEvent.PlaylistRetryExhausted) to RecordingState.Closing action {
                cutFile(EndReason.StreamEnd)
            }
        }

        state(RecordingState.Closing) {
            on(RecordingEvent.CutPointDone) to RecordingState.Idle action { d ->
                launch {
                    component.publish(SessionExit(roomId, lastSegmentId, d?.stopReason ?: EndReason.UserStop))
                    component.publish(RecordingStopped(roomId))
                }
            }
            on(RecordingEvent.StartRecording) to KEEP
            on(RecordingEvent.StopRecording) to KEEP
            on(RecordingEvent.SegmentDownloaded) to KEEP
            on(RecordingEvent.LimitReached) to KEEP
            on(RecordingEvent.PlaylistRetryExhausted) to KEEP
        }
    }

private const val MAX_PLAYLIST_RETRY = 5

// ============================================================
// Actor
// ============================================================

class SessionComponent(
    internal val dataChannel: DataChannel,
    internal val downloader: DownloaderComponent,
    internal val m3u8Parser: M3u8Parser,
    internal val requestBus: RequestBus,
    eventBus: EventBus,
    parentScope: CoroutineScope
) : Actor<SessionMsg>("SessionComponent", eventBus, parentScope) {

    private val entries = ConcurrentHashMap<Long, SessionEntry>()

    internal suspend fun publish(event: Any) = eventBus.publish(event)

    override suspend fun onStart(scope: CoroutineScope) {
        subscribe(SegmentDownloaded::class)
        subscribe(CutPointDone::class)
        subscribe(CommandEnvelope::class)
        subscribe(StopEvent::class)
    }

    override suspend fun wrapEvent(event: Any): SessionMsg? = when (event) {
        is SegmentDownloaded -> SessionBus(event)
        is CutPointDone -> SessionBus(event)
        is CommandEnvelope -> SessionHandleCommand(event)
        is StopEvent -> SessionBus(event)
        else -> null
    }

    override suspend fun handle(msg: SessionMsg) {
        when (msg) {
            is StartRecording -> onStartRecording(msg)
            is StopRecording -> onStopRecording(msg)
            is SessionSignal -> driveFsm(msg)
            is SessionBus -> onBus(msg.event)
            is SessionHandleCommand -> handleCommand(msg.env)
        }
    }

    private suspend fun handleCommand(env: CommandEnvelope) {
        val ack = when (env.command) {
            is GetSessions -> entries.values.map { e ->
                RoomSession(
                    roomId = e.roomId,
                    roomName = e.roomName,
                    quality = e.quality,
                    state = when (e.fsm.currentState) {
                        RecordingState.Recording -> SessionState.Recording
                        RecordingState.Closing -> SessionState.Recording   // still active until CutPointDone
                        else -> SessionState.Idle
                    },
                    startTime = e.startTime
                )
            }
            else -> return
        }
        eventBus.publish(CommandAck(env.id, ack))
    }

    private fun onStartRecording(msg: StartRecording) {
        val e = entries.getOrPut(msg.roomId) { SessionEntry(msg.roomId, msg.roomName, this) }
        if (e.fsm.currentState != RecordingState.Idle) {
            logger.warn("StartRecording ignored: room {} in state {}", msg.roomId, e.fsm.currentState)
            return
        }
        e.roomName = msg.roomName
        e.playlistUrl = msg.playlistUrl
        e.pkey = msg.pkey
        e.quality = msg.quality
        e.startIndex = msg.startIndex
        e.timeLimit = msg.timeLimit
        e.sizeLimitBytes = msg.sizeLimitBytes
        e.generation = SecureRandom().nextLong()
        e.lastSegmentId = msg.startIndex?.minus(1)
        e.lastInitUrl = null
        e.fsm.driveCatch(RecordingEvent.StartRecording)?.let { logger.error("Session FSM drive failed", it) }
    }

    private fun onStopRecording(msg: StopRecording) {
        val e = entries[msg.roomId] ?: return
        e.fsm.driveCatch(RecordingEvent.StopRecording, RecordingDriveData(stopReason = msg.reason))
            ?.let { logger.error("Session FSM drive failed", it) }
    }

    private fun driveFsm(sig: SessionSignal) {
        val e = entries[sig.roomId] ?: return
        e.fsm.driveCatch(sig.event, sig.data)?.let { logger.error("Session FSM drive failed", it) }
    }

    private fun onBus(event: Any) {
        when (event) {
            is SegmentDownloaded -> {
                val e = entries[event.roomId] ?: return
                e.fsm.driveCatch(
                    RecordingEvent.SegmentDownloaded,
                    RecordingDriveData(segBytes = event.bytes.toLong(), segGeneration = event.generation)
                )?.let { logger.error("Session FSM drive failed", it) }
            }
            is CutPointDone -> {
                val e = entries[event.roomId] ?: return
                e.fsm.driveCatch(
                    RecordingEvent.CutPointDone,
                    RecordingDriveData(segGeneration = event.generation, stopReason = event.reason)
                )?.let { logger.error("Session FSM drive failed", it) }
            }
            is StopEvent -> {
                logger.info("Stop event received: stopping all active sessions")
                entries.values.forEach { e ->
                    if (e.fsm.currentState == RecordingState.Recording) {
                        e.fsm.driveCatch(RecordingEvent.StopRecording, RecordingDriveData(stopReason = EndReason.UserStop))
                            ?.let { logger.error("Session FSM drive failed", it) }
                    }
                }
            }
        }
    }
}
