package github.rikacelery.v3.components

import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.DataChannel
import github.rikacelery.v3.core.Diagnosable
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.core.diagnose
import github.rikacelery.v3.data.RuntimeTuning
import github.rikacelery.v3.data.StreamStart
import github.rikacelery.v3.events.*
import github.rikacelery.v3.fsm.KEEP
import github.rikacelery.v3.fsm.LoopTimer
import github.rikacelery.v3.fsm.StateMachine
import github.rikacelery.v3.fsm.Timer
import github.rikacelery.v3.fsm.buildFsm
import github.rikacelery.v3.m3u8.M3u8Parser
import github.rikacelery.v3.m3u8.ParsedPlaylist
import github.rikacelery.v3.utils.CdnSelector
import github.rikacelery.v3.utils.DefaultHttpClientProvider
import github.rikacelery.v3.utils.HttpClientProvider
import github.rikacelery.v3.utils.withRetry
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
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
    SegmentDownloaded, CutPointDone, CutPointTimedOut, LimitReached, InitChanged,
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

/**
 * Renders a segment-id range for logs: a single id when both ends are the same, so a one-element
 * set does not read as a typo (`id 1063` rather than `id 1063..1063`).
 */
internal fun formatIdRange(min: Long?, max: Long?): String = when {
    min == null || max == null -> "?"
    min == max -> min.toString()
    else -> "$min..$max"
}

class CircleCache(private val capacity: Int) {
    private val set = LinkedHashSet<String>()

    /**
     * Records [url] and reports whether it is new. Repeats are always reported as seen, even when
     * the cache is full — and a genuinely new url evicts only the oldest entry. The previous
     * implementation cleared the whole set when full and returned `true`, so the constantly
     * re-listed init URL was reported as new and re-injected into the middle of the file.
     */
    @Synchronized fun add(url: String): Boolean {
        if (set.contains(url)) return false
        if (set.size >= capacity) {
            set.firstOrNull()?.let { set.remove(it) }
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
    /**
     * Decrypt key for [pkey], resolved once per session. It was looked up over the request bus on
     * every playlist poll — once per 3 s per recording room for a value that cannot change while
     * the session's `pkey` is fixed.
     */
    var decryptKey: String? = null
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

    /**
     * When this session last made progress: a new media segment entered the download queue, or one
     * finished downloading. A session that keeps polling a playlist with no media segments would
     * otherwise sit in `Recording` forever while the dashboard reports a recording that never
     * writes a byte, so the poll loop watches this and ends the session once it goes stale.
     */
    var lastProgressAt: Instant = Instant.now()
    var retryCount: Int = 0

    // —— Resume-mark backlog accounting ——
    // A healthy playlist *overlaps* the resume mark by a segment or two — the mark and the window
    // join up — and the few entries that fall below the mark are simply already written. That is
    // normal and silent. What is worth reporting is the mark running far *ahead* of the playlist:
    // then every advertised segment is already recorded and nothing can be downloaded until the
    // stream catches up, which is what a mark left from an earlier broadcast looks like.
    var lastPollMarkAhead: Long = 0L
    var lastPollMarkAheadJustReported: Boolean = false
    var markAheadReported: Boolean = false
    var markAheadSince: Instant? = null

    /**
     * Whether the most recent poll enqueued at least one *media* segment. The init segment is
     * enqueued regardless of the resume mark, so it must not count as progress: a session that
     * only ever downloads the init is exactly the stall the skip streak reports.
     */
    var lastPollEnqueuedMedia: Boolean = false

    /**
     * Media segments advertised by the most recent playlist. Together with [lastPollEnqueuedMedia]
     * and [lastSegmentId] this separates the two ways a session can idle in `Recording`: a playlist
     * that carries no segments at all, versus one whose segments are all behind the resume mark.
     */
    var lastPollSegmentCount: Int = 0

    /** Playlist entries the most recent poll skipped because the resume mark already covered them. */
    var lastPollSkipped: Int = 0

    /**
     * Highest segment id this session has already queued, used to notice a *discontinuity*: if the
     * playlist advances from id N to id M > N + 1 between two polls, the ids in between were
     * published by the stream but never advertised to us, so they are lost.
     *
     * Deliberately session-local (reset on every `StartRecording`), unlike the resume mark: a room
     * that was restarted, or was not being polled at all, has an expected gap at the seam, and
     * counting that as data loss would flag every limit cut.
     */
    var previousNewSegmentId: Long? = null

    /** Missing segments detected by the most recent poll (a discontinuity in the segment ids). */
    var lastPollGap: Int = 0
    val circleCache = CircleCache(100)

    /**
     * CDN hosts this session already fetched the playlist from.
     *
     * The playlist URL is resolved exactly once, by the scheduler, before the session starts
     * (`SchedulerEntry.resolveVariantUrl`); nothing downstream re-resolves it. A host that stops
     * answering therefore keeps being polled until the session gives up on the whole recording and
     * the scheduler preconfigures again — which picks the same host, because a failed playlist
     * fetch never told [CdnSelector] anything. This set is what lets the session walk to the next
     * host instead. It is deliberately session-local rather than a global host penalty: the same
     * CDN serves other rooms' segments fine, so any host-level failure count would be reset by
     * their next success long before it cooled the host down.
     */
    val playlistHostsTried = LinkedHashSet<String>()

    // —— scope / loop timer ——
    val scope = CoroutineScope(
        component.ioScope.coroutineContext + SupervisorJob(component.ioScope.coroutineContext[Job])
    )
    val playlistLoop = LoopTimer<Unit>(scope)

    /**
     * Fires when a cut point never comes back. `CutPointDone` travels over the event bus, which
     * drops events under load, and the downloader can stall up to its own deadline; the watchdog
     * bounds that wait so a session always leaves Closing and the scheduler always leaves Stopping
     * instead of hanging on a signal that will never arrive.
     */
    val cutWatchdog = Timer<Unit>(scope)

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
        // A session that ends for good releases its playlist client: a pool that went bad must not
        // be inherited by the next session of this room, and an idle room should not hold sockets.
        // A time/size limit restarts on the *same* stream seconds later, so that one keeps it.
        if (reason != EndReason.TimeLimit && reason != EndReason.SizeLimit) {
            component.httpClientProvider.evict("m3u8_$roomId")
        }
        launch {
            component.downloader.tell(
                DoCutPoint(CutPoint(roomId, segmentIndex - 1, roomName, Instant.now(), reason, quality, generation))
            )
        }
        // CutPointDone returns over the event bus, which may drop it; bound the wait so the session
        // still leaves Closing (and the scheduler Stopping) when it never arrives.
        cutWatchdog.start(component.runtimeTuning.sessionCutTimeout) {
            sessionLogger.warn(
                "Cut point timed out roomId={}, reason={}: ending the session without CutPointDone",
                roomId, reason
            )
            component.tell(
                SessionSignal(roomId, RecordingEvent.CutPointTimedOut, RecordingDriveData(stopReason = reason))
            )
        }
    }

    internal fun onSegment(d: RecordingDriveData?) {
        val gen = d?.segGeneration ?: return
        if (gen != generation) return
        lastProgressAt = Instant.now()
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
        lastPollEnqueuedMedia = false
        lastPollSegmentCount = parsed.segments.size
        parsed.initUrl?.let { init ->
            if (circleCache.add(init)) {
                unseen.add(Segment(init, -1))
            }
        }
        val markBefore = lastSegmentId
        var skipped = 0L
        var skippedMin = Long.MAX_VALUE
        var skippedMax = Long.MIN_VALUE
        var advertisedMax = Long.MIN_VALUE
        val newIds = mutableListOf<Long>()
        for (seg in parsed.segments) {
            val segId = component.m3u8Parser.segmentIDFromUrl(seg.url)?.toLong()
            if (segId != null && segId > advertisedMax) advertisedMax = segId
            if (segId != null) {
                val threshold = lastSegmentId
                if (threshold != null && segId <= threshold) {
                    skipped++
                    if (segId < skippedMin) skippedMin = segId
                    if (segId > skippedMax) skippedMax = segId
                    continue
                }
            }
            if (circleCache.add(seg.url)) {
                unseen.add(seg)
                lastPollEnqueuedMedia = true
                if (segId != null) newIds += segId
            }
        }
        if (newIds.isNotEmpty()) lastSegmentId = newIds.max()
        noteSegmentGap(newIds)
        noteMarkAhead(markBefore, advertisedMax.takeIf { it != Long.MIN_VALUE })
        lastPollSkipped = skipped.toInt()
        if (skipped > 0) {
            // Which entries fell below the mark: one aggregated line per poll, never per segment.
            // This is TRACE, not DEBUG, because an overlap is the *steady state* of a healthy
            // recording, so at DEBUG (the default root level) it would be unconditional noise on
            // every poll of every room.
            //
            // Both numbers matter and neither is the window: `id` is the skipped set's own range
            // (a single id when one entry was skipped), and the mark is printed as a transition
            // because it has already advanced to this poll's newest id by the time we get here.
            sessionLogger.trace(
                "roomId={} skipped {} of {} advertised segment(s): id {} already at or below the resume mark (mark {} -> {})",
                roomId, skipped, parsed.segments.size, formatIdRange(skippedMin, skippedMax), markBefore, lastSegmentId
            )
        }
        return unseen
    }

    /**
     * Called once per poll. Reports a resume mark that has run far ahead of the playlist, and the
     * moment the stream passes it again.
     *
     * The mark normally sits at (or just behind) the newest advertised id — the two "join up" — so
     * a small lead means nothing and is not reported. Only a lead beyond
     * [RuntimeTuning.resumeMarkAheadThreshold] is a backlog: everything advertised is already
     * recorded and nothing can be downloaded until the stream advances.
     */
    private fun noteMarkAhead(mark: Long?, advertisedMax: Long?) {
        lastPollMarkAheadJustReported = false
        val ahead = if (mark == null || advertisedMax == null) 0L else mark - advertisedMax
        if (ahead <= component.runtimeTuning.resumeMarkAheadThreshold) {
            if (markAheadReported) {
                val waitedMs = markAheadSince?.let { JavaDuration.between(it, Instant.now()).toMillis() } ?: 0L
                sessionLogger.info(
                    "roomId={} the stream caught up with the resume mark after {}s (it was {} id(s) ahead)",
                    roomId, waitedMs / 1000, lastPollMarkAhead
                )
            }
            resetMarkAhead()
            return
        }
        lastPollMarkAhead = ahead
        if (markAheadReported) return
        markAheadReported = true
        markAheadSince = Instant.now()
        lastPollMarkAheadJustReported = true
        sessionLogger.warn(
            "roomId={} the resume mark is {} segment id(s) ahead of the playlist (mark={}, newest advertised={}); " +
                "everything advertised is already recorded, so nothing can be recorded until the stream catches up",
            roomId, ahead, mark, advertisedMax
        )
    }

    internal fun resetMarkAhead() {
        lastPollMarkAhead = 0L
        lastPollMarkAheadJustReported = false
        markAheadReported = false
        markAheadSince = null
    }

    /**
     * Compares this poll's newly queued segment ids with the previous poll's to detect a
     * discontinuity. Ids the stream published but never advertised to us — because the playlist
     * advanced past them between two polls, or because the window itself carries a hole — are lost,
     * and nothing else in the pipeline can see that: a segment we never hear about never fails and
     * never reaches the downloader.
     *
     * Every id from `previous + 1` up to the newest id is expected exactly once, so the count is
     * simply how many of those are absent. The first poll that queues anything only establishes the
     * baseline: the session has no earlier observation to compare against, and after a restart the
     * seam is expected rather than lost.
     */
    private fun noteSegmentGap(newIds: List<Long>) {
        lastPollGap = 0
        if (newIds.isEmpty()) return
        val previous = previousNewSegmentId
        previousNewSegmentId = if (previous == null) newIds.max() else maxOf(previous, newIds.max())
        if (previous == null) return

        var expected = previous + 1
        var missing = 0L
        for (id in newIds.sorted()) {
            if (id >= expected) missing += id - expected
            expected = maxOf(expected, id + 1)
        }
        if (missing <= 0) return
        lastPollGap = missing.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        sessionLogger.debug(
            "roomId={} playlist went from segment id {} to {}; {} id(s) in between were never advertised",
            roomId, previous, newIds.max(), missing
        )
    }

    internal suspend fun fetchPlaylistSignal(): SessionMsg {
        return try {
            withTimeout(component.runtimeTuning.playlistFetchTimeout) {
                val client = component.httpClientProvider.proxied("m3u8_$roomId")
                val attemptMs = component.runtimeTuning.playlistAttemptTimeout.inWholeMilliseconds
                val startedAt = System.currentTimeMillis()
                val response = withRetry(3) {
                    client.get(playlistUrl) {
                        timeout {
                            // Engine-level timeouts, deliberately below playlistFetchTimeout: a
                            // pooled connection that stopped answering is aborted here, so OkHttp
                            // drops it and HttpRequestRetry can dial a fresh one. Without this the
                            // whole fetch budget is spent on one dead connection as a coroutine
                            // cancellation, which no retry layer can observe.
                            socketTimeoutMillis = attemptMs
                            connectTimeoutMillis = attemptMs
                        }
                    }
                }
                val text = response.bodyAsText()
                val latencyMs = System.currentTimeMillis() - startedAt
                val key = decryptKey ?: run {
                    val fetched = component.requestBus.request<ConfigResponse>(GetDecryptKey(pkey)).value as? String
                    if (fetched != null) decryptKey = fetched
                    fetched
                } ?: return@withTimeout SessionSignal(
                    roomId, RecordingEvent.PlaylistFetchFailed,
                    RecordingDriveData(failReason = "no decrypt key")
                )
                val parsed = component.m3u8Parser.parse(text, key)
                CdnSelector.recordPlaylistSuccess(CdnSelector.hostOf(playlistUrl))
                // The only producer of this event: without it `xhrec_refresh_latency_ms` and
                // `xhrec_segment_id_current` are declared, consumed and scraped, but stay at 0
                // forever. The latency covers the whole fetch including retries and the body read,
                // which is what "how long did this refresh take" means to an operator.
                component.publish(
                    PlaylistRefreshed(roomId, latencyMs, parsed.segments.maxOfOrNull { it.index } ?: 0)
                )
                SessionSignal(roomId, RecordingEvent.PlaylistFetched, RecordingDriveData(playlist = parsed))
            }
        } catch (e: TimeoutCancellationException) {
            failOverPlaylistHost()
            SessionSignal(roomId, RecordingEvent.PlaylistFetchFailed, RecordingDriveData(failReason = "timeout"))
        } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.NotFound || e.response.status == HttpStatusCode.Forbidden) {
                refreshRoomStatus()
                SessionSignal(roomId, RecordingEvent.PlaylistUnusable, RecordingDriveData(failReason = e.response.status.toString()))
            } else {
                failOverPlaylistHost()
                SessionSignal(roomId, RecordingEvent.PlaylistFetchFailed, RecordingDriveData(failReason = e.message))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failOverPlaylistHost()
            SessionSignal(roomId, RecordingEvent.PlaylistFetchFailed, RecordingDriveData(failReason = e.message))
        }
    }

    /**
     * Move this session's playlist request to the next-best CDN host after a transport failure, and
     * report the failure to [CdnSelector] so the host is penalized for the *next* preconfig too.
     *
     * Two levels, in this order:
     *  1. drop this room's playlist client, so the retry cannot be handed the same pooled
     *     connection that just failed;
     *  2. move to a host outside [playlistHostsTried]. A plain `CdnSelector.resolve` would hand back
     *     the same host, because its segment score is still the best one and playlist penalties live
     *     apart from it. The session therefore keeps its own tried set — the same shape the segment
     *     downloader uses — with a second pass over all hosts once every host has been tried
     *     (playlist cooldowns are short, and the only remaining host may still be the working one).
     *
     * Returns true when the playlist actually moved to a different host.
     */
    internal fun failOverPlaylistHost(): Boolean {
        val failed = CdnSelector.hostOf(playlistUrl)
        if (failed.isEmpty()) return false
        component.httpClientProvider.evict("m3u8_$roomId")
        CdnSelector.recordPlaylistFailure(failed)
        playlistHostsTried += failed
        val next = CdnSelector.rankedPlaylistHosts(exclude = playlistHostsTried).firstOrNull()
            ?: CdnSelector.rankedPlaylistHosts(includeCooling = true).firstOrNull()
            ?: return false
        if (next == failed) return false
        playlistUrl = CdnSelector.rewriteHost(playlistUrl, next)
        sessionLogger.warn(
            "roomId={} playlist fetch failed on {}; switching the playlist to {}",
            roomId, failed, next
        )
        return true
    }

    private suspend fun refreshRoomStatus() {
        try {
            // coalesce: the room may fail preconfig right after this session, and that second
            // failure means the same thing as this one
            component.requestBus.request<OkResponse>(RefreshRoomCmd(roomId, coalesce = true))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sessionLogger.warn("Playlist unavailable and room status refresh failed roomId={}: {}", roomId, e.message)
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
                lastProgressAt = startTime
                segmentIndex = 0
                totalBytes = 0L
                retryCount = 0
                resetMarkAhead()
                previousNewSegmentId = null
                lastPollGap = 0
                circleCache.clear()   // a new file must re-download the init; media segments are skipped via the lastSegmentId threshold
                // the playlist host is re-resolved by the scheduler for every new session
                playlistHostsTried.clear()
                launch { component.dataChannel.send(StreamStart(roomId, roomName, startTime, quality)) }
                // LiveEventSource expands the WebSocket channel set and metrics start counting on this
                launch { component.publish(RecordingStarted(roomId, quality)) }
                playlistLoop.start(component.runtimeTuning.playlistPollInterval) { component.tell(fetchPlaylistSignal()) }
            }
            on(RecordingEvent.StopRecording) to KEEP
            on(RecordingEvent.SegmentDownloaded) to KEEP
            on(RecordingEvent.CutPointDone) to KEEP
            on(RecordingEvent.CutPointTimedOut) to KEEP
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
                    lastProgressAt = Instant.now()
                    launch { component.downloader.tell(DoDownload(Download(roomId, unseen, segmentIndex, generation))) }
                }
                // Report a resume mark that has run far ahead of the playlist. Only this reported
                // case reaches the metric: the ordinary overlap of the window with the mark happens
                // on every poll of every healthy room, so counting it would make the counter grow
                // forever without ever meaning anything.
                if (lastPollMarkAheadJustReported) {
                    launch {
                        component.publish(SegmentsSkipped(roomId, lastPollMarkAhead.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
                    }
                    lastPollMarkAheadJustReported = false
                }
                // A discontinuity is real data loss and nothing downstream can observe it: a segment
                // that was never advertised never fails and never reaches the downloader.
                if (lastPollGap > 0) {
                    launch { component.publish(SegmentGapDetected(roomId, lastPollGap)) }
                }
                // An init-only (or segment-less) playlist is a stream that is not delivering:
                // without this the session reports Recording forever and writes nothing.
                val stalledMs = JavaDuration.between(lastProgressAt, Instant.now()).toMillis()
                if (stalledMs >= component.runtimeTuning.sessionStallTimeout.inWholeMilliseconds) {
                    sessionLogger.warn(
                        "Recording stalled roomId={}: no segment for {}s (playlist carries {} media segment(s)); " +
                            "ending the session so the room re-resolves its stream",
                        roomId, stalledMs / 1000, parsed.segments.size
                    )
                    launch {
                        component.tell(
                            SessionSignal(
                                roomId, RecordingEvent.PlaylistUnusable,
                                RecordingDriveData(failReason = "no segment for ${stalledMs / 1000}s")
                            )
                        )
                    }
                    return@action
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
                cutWatchdog.cancel()
                launch {
                    component.publish(SessionExit(roomId, lastSegmentId, d?.stopReason ?: EndReason.UserStop))
                    component.publish(RecordingStopped(roomId))
                }
            }
            // The cut point never came back: its event was dropped, or the downloader stalled past
            // its deadline. End the session anyway so the room cannot stay in Stopping forever.
            on(RecordingEvent.CutPointTimedOut) to RecordingState.Idle action { d ->
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
            // A playlist fetch already in flight when the cut started still answers after the
            // transition. The session is closing, so the answer carries nothing to act on —
            // dropping it is correct and keeps it from reading as an illegal transition.
            on(RecordingEvent.PlaylistFetched) to KEEP
            on(RecordingEvent.PlaylistFetchFailed) to KEEP
            on(RecordingEvent.PlaylistUnusable) to KEEP
            on(RecordingEvent.InitChanged) to KEEP
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
    parentScope: CoroutineScope,
    internal val httpClientProvider: HttpClientProvider = DefaultHttpClientProvider,
    internal val runtimeTuning: RuntimeTuning = RuntimeTuning()
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
            logger.warn("roomId={} StartRecording ignored in state {}", msg.roomId, e.fsm.currentState)
            return
        }
        e.roomName = msg.roomName
        e.playlistUrl = msg.playlistUrl
        e.pkey = msg.pkey
        e.decryptKey = null
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

    // —— Diagnostics ——

    override val diagnoseSections: List<String>
        get() = listOf(Diagnosable.SECTION_SUMMARY, Diagnosable.SECTION_ENTRIES, Diagnosable.SECTION_HISTORY)

    /**
     * `/diagnose?actor=SessionComponent[&section=entries|history][&room=<id>]`
     *
     * This is where a "recording but downloading nothing" room is explained: the resume mark
     * (`lastSegmentId`), the segment counter, when the session last made progress, and whether
     * the playlist poll loop is still running.
     */
    override suspend fun diagnose(section: String, args: Map<String, String>): JsonObject {
        val roomFilter = args["room"]?.toLongOrNull()
        val selected = entries.values
            .filter { roomFilter == null || it.roomId == roomFilter }
            .sortedBy { it.roomId }
        return baseDiagnose(buildJsonObject {
            put("sessionCount", entries.size)
            put("states", buildJsonObject {
                entries.forEach { (id, e) -> put(id.toString(), JsonPrimitive(e.fsm.currentState.toString())) }
            })
            if (section == Diagnosable.SECTION_HISTORY) {
                put("history", buildJsonObject {
                    selected.forEach { put(it.roomId.toString(), it.fsm.diagnose()["history"] ?: JsonArray(emptyList())) }
                })
            } else {
                put("entries", buildJsonArray { selected.forEach { add(it.diagnoseJson()) } })
            }
        })
    }
}

/** One session entry as an operator needs it; the playlist URL keeps only its path. */
internal fun SessionEntry.diagnoseJson(): JsonObject = buildJsonObject {
    put("roomId", roomId)
    put("roomName", roomName)
    put("quality", quality)
    put("playlistPath", playlistUrl.substringBefore('?'))
    put("startedAt", startTime.toString())
    put("sinceStartMs", JavaDuration.between(startTime, Instant.now()).toMillis())
    put("lastProgressAt", lastProgressAt.toString())
    put("noProgressMs", JavaDuration.between(lastProgressAt, Instant.now()).toMillis())
    put("segmentIndex", segmentIndex)
    put("lastSegmentId", lastSegmentId?.let { JsonPrimitive(it) } ?: JsonNull)
    put("lastInitUrl", lastInitUrl?.substringBefore('?')?.let { JsonPrimitive(it) } ?: JsonNull)
    put("totalBytes", totalBytes)
    put("retryCount", retryCount)
    // -1 means unlimited. Exposed so a restarted session's applied limit is directly checkable
    // instead of being inferred from whether it happened to cut again.
    put("timeLimitMs", if (timeLimit.isInfinite()) -1L else timeLimit.inWholeMilliseconds)
    put("sizeLimitBytes", sizeLimitBytes)
    put("playlistLoopRunning", playlistLoop.isRunning)
    // Which hosts the playlist was already moved off in this session: the answer to "the playlist
    // keeps timing out, why is it still on the same CDN?".
    put("playlistHostsTried", buildJsonArray { playlistHostsTried.forEach { add(JsonPrimitive(it)) } })
    put("lastPollSegmentCount", lastPollSegmentCount)
    put("lastPollSkipped", lastPollSkipped)
    put("lastPollGap", lastPollGap)
    put("previousNewSegmentId", previousNewSegmentId?.let { JsonPrimitive(it) } ?: JsonNull)
    put("lastPollEnqueuedMedia", lastPollEnqueuedMedia)
    put("resumeMarkAhead", buildJsonObject {
        put("ahead", lastPollMarkAhead)
        put("reported", markAheadReported)
        put("since", markAheadSince?.toString()?.let { JsonPrimitive(it) } ?: JsonNull)
    })
    put("fsm", fsm.diagnose())
}
