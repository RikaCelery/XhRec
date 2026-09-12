package github.rikacelery.v3.components

import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.PipelineMetrics
import github.rikacelery.v3.core.RoomStateRegistry
import github.rikacelery.v3.events.*
import github.rikacelery.v3.utils.CdnSelector
import github.rikacelery.v3.utils.HttpConnectionStats
import github.rikacelery.v3.utils.ModelSchedule
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

sealed interface MetricMsg
data class OnMetricEvent(val event: Any) : MetricMsg
data class HandleMetricCommand(val env: CommandEnvelope) : MetricMsg

data class RunningUrlInfo(val type: String, val startAt: Long)

data class RoomMetrics(
    // per-segment counters (reset on FileReady)
    val segmentAttempted: AtomicLong = AtomicLong(0),
    val segmentDownloaded: AtomicLong = AtomicLong(0),
    val segmentFailed: AtomicLong = AtomicLong(0),
    val segmentBytes: AtomicLong = AtomicLong(0),
    // lifetime counters (never reset)
    val lifetimeAttempted: AtomicLong = AtomicLong(0),
    val lifetimeDownloaded: AtomicLong = AtomicLong(0),
    val lifetimeFailed: AtomicLong = AtomicLong(0),
    val lifetimeBytes: AtomicLong = AtomicLong(0),
    // unchanged
    val proxyCount: AtomicLong = AtomicLong(0),
    val directCount: AtomicLong = AtomicLong(0),
    val latencySamples: ArrayDeque<Long> = ArrayDeque(10),
    val refreshLatencySamples: ArrayDeque<Long> = ArrayDeque(10),
    @Volatile var quality: String = "",
    val currentSegmentId: AtomicLong = AtomicLong(0),
    val totalLatencyMs: AtomicLong = AtomicLong(0),
    val fileCount: AtomicLong = AtomicLong(0),
    val segmentMissing: AtomicLong = AtomicLong(0),
    /** Playlist entries skipped because the resume mark already covered them (never reset). */
    val segmentsSkipped: AtomicLong = AtomicLong(0),
    /** Cut and session-exit reasons, so "why did this recording end" is a label. */
    val cutReasons: ConcurrentHashMap<String, AtomicLong> = ConcurrentHashMap(),
    val stopReasons: ConcurrentHashMap<String, AtomicLong> = ConcurrentHashMap(),
    val runningUrls: ConcurrentHashMap<String, RunningUrlInfo> = ConcurrentHashMap(),
    /**
     * A recording session is running. Gates the session-scoped gauges (current file size, segment
     * id, in-flight downloads, latency samples, quality) so a room that stopped recording stops
     * exporting "what the current file looks like" — there is no current file.
     *
     * The same concept the `xhrec_recording` gauge reports; the two should be unified if both land.
     */
    @Volatile var sessionActive: Boolean = false
)

class MetricComponent(
    eventBus: EventBus,
    parentScope: CoroutineScope
) : Actor<MetricMsg>("MetricComponent", eventBus, parentScope) {

    private val metrics = ConcurrentHashMap<Long, RoomMetrics>()

    override suspend fun onStart(scope: CoroutineScope) {
        // One collector preserves ordering across event types, including immediate
        // failures and file rotations. wrapEvent filters out unrelated events.
        subscribe(Any::class)
    }

    override suspend fun wrapEvent(event: Any): MetricMsg? = when (event) {
        is SegmentDownloaded -> OnMetricEvent(event)
        is DownloadError -> OnMetricEvent(event)
        is DownloadStarted -> OnMetricEvent(event)
        is SegmentGapDetected -> OnMetricEvent(event)
        is SegmentsSkipped -> OnMetricEvent(event)
        is CutPointDone -> OnMetricEvent(event)
        is SessionExit -> OnMetricEvent(event)
        is WsReconnected -> OnMetricEvent(event)
        is WsDisconnected -> OnMetricEvent(event)
        is FileReady -> OnMetricEvent(event)
        is FileProcessed -> OnMetricEvent(event)
        is PlaylistRefreshed -> OnMetricEvent(event)
        is RecordingStarted -> OnMetricEvent(event)
        is RecordingStopped -> OnMetricEvent(event)
        is RoomStatusChanged -> OnMetricEvent(event)
        is RoomRemoved -> OnMetricEvent(event)
        is CommandEnvelope -> HandleMetricCommand(event)
        else -> null
    }

    override suspend fun handle(msg: MetricMsg) {
        when (msg) {
            is HandleMetricCommand -> {
                val ack = when (msg.env.command) {
                    is GetRoomDetailedStatus -> {
                        metrics.mapValues { (_, m) ->
                            mapOf<String, Any>(
                                "total" to (m.segmentAttempted.get() + m.runningUrls.size),
                                "success" to m.segmentDownloaded.get(),
                                "failed" to m.segmentFailed.get(),
                                "bytesWrite" to m.segmentBytes.get(),
                                "lifetimeBytes" to m.lifetimeBytes.get(),
                                "lifetimeDownloaded" to m.lifetimeDownloaded.get(),
                                "running" to m.runningUrls.mapKeys { it.key }
                                    .mapValues { (_, v) ->
                                        mapOf<String, Any>(
                                            "type" to v.type,
                                            "startAt" to v.startAt
                                        )
                                    }
                            )
                        }
                    }

                    else -> return
                }
                eventBus.publish(CommandAck(msg.env.id, ack))
            }

            is OnMetricEvent -> when (val e = msg.event) {
                is DownloadStarted -> {
                    val m = metrics.getOrPut(e.roomId) { RoomMetrics() }
                    m.segmentAttempted.incrementAndGet()
                    m.lifetimeAttempted.incrementAndGet()
                    m.runningUrls[e.url] = RunningUrlInfo("DIRECT", e.timestamp)
                }

                is SegmentDownloaded -> {
                    val m = metrics.getOrPut(e.roomId) { RoomMetrics() }
                    m.segmentDownloaded.incrementAndGet()
                    m.segmentBytes.addAndGet(e.bytes.toLong())
                    m.lifetimeDownloaded.incrementAndGet()
                    m.lifetimeBytes.addAndGet(e.bytes.toLong())
                    synchronized(m) {
                        m.latencySamples.addLast(e.durationMs)
                        if (m.latencySamples.size > 10) m.latencySamples.removeFirst()
                    }
                    if (e.proxied) m.proxyCount.incrementAndGet() else m.directCount.incrementAndGet()
                    m.runningUrls.remove(e.originalUrl)
                }

                is DownloadError -> {
                    val m = metrics.getOrPut(e.roomId) { RoomMetrics() }
                    m.segmentFailed.incrementAndGet()
                    m.lifetimeFailed.incrementAndGet()
                    e.url?.let { m.runningUrls.remove(it) }
                }

                is SegmentGapDetected -> {
                    // accumulate: the metric is declared a counter and named _total, and one gap
                    // event carries only that event's missing count
                    metrics.getOrPut(e.roomId) { RoomMetrics() }.segmentMissing.addAndGet(e.gap.toLong())
                }

                is SegmentsSkipped -> {
                    metrics.getOrPut(e.roomId) { RoomMetrics() }.segmentsSkipped.addAndGet(e.count.toLong())
                }

                is CutPointDone -> {
                    metrics.getOrPut(e.roomId) { RoomMetrics() }
                        .cutReasons.computeIfAbsent(e.reason.name) { AtomicLong() }.incrementAndGet()
                }

                is SessionExit -> {
                    metrics.getOrPut(e.roomId) { RoomMetrics() }
                        .stopReasons.computeIfAbsent(e.reason.name) { AtomicLong() }.incrementAndGet()
                }

                is WsReconnected -> PipelineMetrics.recordWsConnected()
                is WsDisconnected -> PipelineMetrics.recordWsDisconnected()

                is FileReady -> {
                    val m = metrics[e.roomId] ?: return
                    m.fileCount.incrementAndGet()
                    m.segmentAttempted.set(0)
                    m.segmentDownloaded.set(0)
                    m.segmentFailed.set(0)
                    m.segmentBytes.set(0)
                }

                is FileProcessed -> {}

                is PlaylistRefreshed -> {
                    val m = metrics.getOrPut(e.roomId) { RoomMetrics() }
                    synchronized(m) {
                        m.refreshLatencySamples.addLast(e.latencyMs)
                        if (m.refreshLatencySamples.size > 10) m.refreshLatencySamples.removeFirst()
                    }
                    m.currentSegmentId.set(e.maxSegmentId.toLong())
                }

                is RecordingStarted -> {
                    val m = metrics.getOrPut(e.roomId) { RoomMetrics() }
                    m.quality = e.quality
                    m.sessionActive = true
                }

                is RecordingStopped -> {
                    // Deliberately keep the counters. RecordingStopped fires per session (a limit
                    // cut restarts the room), so removing here reset every lifetime counter on each
                    // cut and erased the series before anyone could read it post-mortem. The entry is
                    // dropped only when the room itself is removed.
                    //
                    // The session-scoped state does go: it is cleared here so a later scrape cannot
                    // resurrect a stale value even if the gauge filter is ever relaxed.
                    metrics[e.roomId]?.let { m ->
                        m.sessionActive = false
                        m.segmentAttempted.set(0)
                        m.segmentDownloaded.set(0)
                        m.segmentFailed.set(0)
                        m.segmentBytes.set(0)
                        m.currentSegmentId.set(0)
                        m.quality = ""
                        m.runningUrls.clear()
                        synchronized(m) {
                            m.latencySamples.clear()
                            m.refreshLatencySamples.clear()
                        }
                    }
                }

                is RoomRemoved -> metrics.remove(e.roomId)

                is RoomStatusChanged -> {
                    // Record model schedule when room goes live (not offline)
                    if (!github.rikacelery.v3.data.RoomStatus.isOffline(e.newStatus)) {
                        ModelSchedule.record(e.roomId, System.currentTimeMillis())
                    }
                }

                else -> {}
            }
        }
    }

    fun prometheusText(): String {
        val sb = StringBuilder()

        // Prometheus allows one HELP/TYPE block per metric family, so metadata is emitted once per
        // scrape — not repeated inside the per-room (and per-host) loops. Repeated HELP lines make
        // strict scrapers reject the whole payload.
        fun family(name: String, help: String, type: String) {
            sb.appendLine("# HELP $name $help")
            sb.appendLine("# TYPE $name $type")
        }

        family("xhrec_attempted_total", "Total attempted segments", "counter")
        family("xhrec_downloaded_total", "Successfully downloaded segments", "counter")
        family("xhrec_failed_total", "Failed segments", "counter")
        family("xhrec_bytes_write_total", "Bytes written", "gauge")
        family("xhrec_proxy_ratio", "Proxy download ratio", "gauge")
        family("xhrec_success_direct_total", "Direct success count", "counter")
        family("xhrec_success_proxied_total", "Proxied success count", "counter")
        family("xhrec_avg_latency_ms", "Average download latency ms", "gauge")
        family("xhrec_segment_missing_total", "Segments the playlist never advertised (a jump in segment ids)", "counter")
        family("xhrec_segments_skipped_total", "Segment ids the resume mark was ahead of the playlist by, when a backlog was reported", "counter")
        family("xhrec_files_total", "Files produced", "counter")
        family("xhrec_refresh_latency_ms", "Playlist refresh latency ms", "gauge")
        family("xhrec_segment_id_current", "Current segment ID", "gauge")
        family("xhrec_downloading_current", "Currently downloading segments", "gauge")
        family("xhrec_quality", "Recording quality", "gauge")
        family("xhrec_segment_downloaded_current", "Downloaded in current segment", "gauge")
        family("xhrec_room_download_bytes_total", "Total bytes downloaded for a room", "counter")
        family("xhrec_cdn_cooldown", "CDN host cooling down for segment downloads (1) or not (0)", "gauge")
        family("xhrec_room_cut_total", "File cuts, by reason", "counter")
        family("xhrec_room_recordings_stopped_total", "Recording sessions that ended, by reason", "counter")
        family("xhrec_cdn_estimated_duration_ms", "CDN host estimated duration at current time", "gauge")
        family("xhrec_cdn_confidence", "CDN host prediction confidence (0-1)", "gauge")
        family("xhrec_cdn_total_successes", "CDN host total successful downloads", "counter")
        family("xhrec_cdn_total_errors", "CDN host total errors", "counter")
        family("xhrec_cdn_playlist_failures", "CDN host consecutive playlist fetch failures", "gauge")
        family("xhrec_cdn_playlist_cooldown", "CDN host cooling down for playlists (1) or not (0)", "gauge")

        metrics.forEach { (roomId, m) ->
            val total = m.proxyCount.get() + m.directCount.get()
            val proxyRatio = if (total > 0) m.proxyCount.get().toDouble() / total else 0.0

            // Lifetime counters: kept for every room that ever recorded, so a finished session can
            // still be looked at afterwards.
            sb.appendLine("xhrec_attempted_total{roomId=\"$roomId\"} ${m.lifetimeAttempted.get()}")
            sb.appendLine("xhrec_downloaded_total{roomId=\"$roomId\"} ${m.lifetimeDownloaded.get()}")
            sb.appendLine("xhrec_failed_total{roomId=\"$roomId\"} ${m.lifetimeFailed.get()}")
            sb.appendLine("xhrec_proxy_ratio{roomId=\"$roomId\"} $proxyRatio")
            sb.appendLine("xhrec_success_direct_total{roomId=\"$roomId\"} ${m.directCount.get()}")
            sb.appendLine("xhrec_success_proxied_total{roomId=\"$roomId\"} ${m.proxyCount.get()}")
            sb.appendLine("xhrec_segment_missing_total{roomId=\"$roomId\"} ${m.segmentMissing.get()}")
            sb.appendLine("xhrec_segments_skipped_total{roomId=\"$roomId\"} ${m.segmentsSkipped.get()}")
            sb.appendLine("xhrec_files_total{roomId=\"$roomId\"} ${m.fileCount.get()}")
            sb.appendLine("xhrec_room_download_bytes_total{roomId=\"$roomId\"} ${m.lifetimeBytes.get()}")
            m.cutReasons.forEach { (reason, count) ->
                sb.appendLine("xhrec_room_cut_total{roomId=\"$roomId\",reason=\"$reason\"} ${count.get()}")
            }
            m.stopReasons.forEach { (reason, count) ->
                sb.appendLine("xhrec_room_recordings_stopped_total{roomId=\"$roomId\",reason=\"$reason\"} ${count.get()}")
            }

            // Session-scoped gauges: exported only while a session is running. They describe "the
            // file being written right now, and the segments feeding it", so once the session ends
            // the series disappear instead of freezing at their last value — a frozen
            // `xhrec_bytes_write_total` reads as a room that is still recording.
            //
            // Gated on the flag rather than on "the value is non-zero": `downloading_current` is
            // legitimately zero between polls, and skipping it then would make the series flap.
            if (!m.sessionActive) return@forEach

            val avgLatency = synchronized(m) {
                if (m.latencySamples.isNotEmpty()) m.latencySamples.average() else 0.0
            }
            val avgRefreshLatency = synchronized(m) {
                if (m.refreshLatencySamples.isNotEmpty()) m.refreshLatencySamples.average() else 0.0
            }
            sb.appendLine("xhrec_bytes_write_total{roomId=\"$roomId\"} ${m.segmentBytes.get()}")
            sb.appendLine("xhrec_avg_latency_ms{roomId=\"$roomId\"} $avgLatency")
            sb.appendLine("xhrec_refresh_latency_ms{roomId=\"$roomId\"} $avgRefreshLatency")
            sb.appendLine("xhrec_segment_id_current{roomId=\"$roomId\"} ${m.currentSegmentId.get()}")
            sb.appendLine("xhrec_downloading_current{roomId=\"$roomId\"} ${m.runningUrls.size}")
            sb.appendLine("xhrec_quality{roomId=\"$roomId\",quality=\"${m.quality}\"} 1")
            sb.appendLine("xhrec_segment_downloaded_current{roomId=\"$roomId\"} ${m.segmentDownloaded.get()}")
        }

        // CDN host duration metrics
        val now = System.currentTimeMillis()
        val cdnStats = CdnSelector.snapshot()
        cdnStats.forEach { (host, stat) ->
            val durLabel = if (stat.estimatedDurationMs.isNaN()) "-1" else stat.estimatedDurationMs.toLong().toString()
            sb.appendLine("xhrec_cdn_estimated_duration_ms{host=\"$host\",source=\"${stat.estimateSource}\"} $durLabel")
            sb.appendLine("xhrec_cdn_confidence{host=\"$host\"} ${stat.confidence}")
            sb.appendLine("xhrec_cdn_total_successes{host=\"$host\"} ${stat.totalSuccesses}")
            sb.appendLine("xhrec_cdn_total_errors{host=\"$host\"} ${stat.totalErrors}")
            sb.appendLine("xhrec_cdn_playlist_failures{host=\"$host\"} ${stat.playlistFailures}")
            sb.appendLine("xhrec_cdn_playlist_cooldown{host=\"$host\"} ${if (stat.playlistCooldownUntil > now) 1 else 0}")
            sb.appendLine("xhrec_cdn_cooldown{host=\"$host\"} ${if (stat.cooldownUntil > now) 1 else 0}")
        }

        // Connection-level telemetry for the human-paced clients (playlist / master / preconfig),
        // which is what separates the two causes of a playlist timeout:
        //   reused climbing and waitHeadersAvg ≈ the per-attempt timeout -> dead pooled connection
        //   newConnections climbing and connectAvg high                 -> host/edge is slow to dial
        // The first is answered by the client eviction, the second by rotating the host.
        val connStats = HttpConnectionStats.snapshot()
        if (connStats.isNotEmpty()) {
            sb.appendLine("# HELP xhrec_http_requests_total Requests observed on a human-paced HTTP client")
            sb.appendLine("# TYPE xhrec_http_requests_total counter")
            sb.appendLine("# HELP xhrec_http_new_connections_total Connections dialed for those requests")
            sb.appendLine("# TYPE xhrec_http_new_connections_total counter")
            sb.appendLine("# HELP xhrec_http_reused_connections_total Requests served from a pooled connection")
            sb.appendLine("# TYPE xhrec_http_reused_connections_total counter")
            sb.appendLine("# HELP xhrec_http_connect_seconds_total Time spent dialing those connections")
            sb.appendLine("# TYPE xhrec_http_connect_seconds_total counter")
            sb.appendLine("# HELP xhrec_http_wait_headers_seconds_total Time from request headers to response headers")
            sb.appendLine("# TYPE xhrec_http_wait_headers_seconds_total counter")
            sb.appendLine("# HELP xhrec_http_failures_total Failed calls, by failure phase")
            sb.appendLine("# TYPE xhrec_http_failures_total counter")
            sb.appendLine("# HELP xhrec_http_timeouts_total Failed calls that were timeouts")
            sb.appendLine("# TYPE xhrec_http_timeouts_total counter")
            sb.appendLine("# HELP xhrec_http_cancelled_total Calls cancelled before completion")
            sb.appendLine("# TYPE xhrec_http_cancelled_total counter")
            connStats.forEach { s ->
                val labels = "role=\"${s.role}\",host=\"${s.host}\""
                sb.appendLine("xhrec_http_requests_total{$labels} ${s.requests}")
                sb.appendLine("xhrec_http_new_connections_total{$labels} ${s.newConnections}")
                sb.appendLine("xhrec_http_reused_connections_total{$labels} ${s.reusedConnections}")
                sb.appendLine("xhrec_http_connect_seconds_total{$labels} ${s.connectMsTotal / 1000.0}")
                sb.appendLine("xhrec_http_wait_headers_seconds_total{$labels} ${s.waitHeadersMsTotal / 1000.0}")
                sb.appendLine("xhrec_http_failures_total{$labels} ${s.failures}")
                sb.appendLine("xhrec_http_timeouts_total{$labels} ${s.timeouts}")
                sb.appendLine("xhrec_http_cancelled_total{$labels} ${s.cancelled}")
            }
        }

        // Bus, data channel and actor counters are process-wide and live in the objects that own
        // them; they are pulled here rather than pushed as events (see PipelineMetrics).
        PipelineMetrics.appendMetrics(sb)
        RoomStateRegistry.appendMetrics(sb, now)

        return sb.toString()
    }
}
