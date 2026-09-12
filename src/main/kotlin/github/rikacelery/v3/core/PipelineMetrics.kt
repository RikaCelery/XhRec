package github.rikacelery.v3.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide counters for the internal transports and the actor mailboxes, rendered by the
 * Prometheus exporter.
 *
 * These are **pulled** at scrape time rather than published as events, for two concrete reasons:
 *
 *  - `EventBus` cannot report its own drops through itself. Each report would be another event that
 *    can be dropped, and counting published events by publishing an event recurses.
 *  - a per-message `AtomicLong` increment is cheaper than a bus round trip, and the counters that
 *    matter most here are on the hottest paths in the recorder.
 *
 * Everything is keyed by a bounded label (event type, message type, actor name), so cardinality is
 * a few dozen series in total.
 */
object PipelineMetrics {

    // ── Event bus ─────────────────────────────────────────────────────────────

    private val eventsPublished = ConcurrentHashMap<String, AtomicLong>()
    private val eventsDropped = ConcurrentHashMap<String, AtomicLong>()

    /** True while the bus buffer is saturated and events are being dropped. */
    @Volatile
    var eventBusBacklogged: Boolean = false
        private set

    /** Events dropped since start; monotonic, unlike the per-episode total the logger reports. */
    val eventBusDroppedTotal = AtomicLong(0)

    fun recordEventPublished(type: String) {
        eventsPublished.bump(type)
    }

    /** Records one dropped event and enters the backlogged state. */
    fun recordEventDropped(type: String) {
        eventsDropped.bump(type)
        eventBusDroppedTotal.incrementAndGet()
        eventBusBacklogged = true
    }

    fun clearEventBacklog() {
        eventBusBacklogged = false
    }

    // ── Data channel (downloader → writer) ────────────────────────────────────

    private val channelSent = ConcurrentHashMap<String, AtomicLong>()
    private val channelDropped = ConcurrentHashMap<String, AtomicLong>()

    /** Messages accepted but not yet consumed by the writer. */
    val channelPending = AtomicInteger(0)

    /** Media bytes handed to the channel. */
    val channelBytes = AtomicLong(0)

    fun recordChannelSent(type: String, bytes: Int) {
        channelSent.bump(type)
        channelBytes.addAndGet(bytes.toLong())
    }

    fun recordChannelDropped(type: String) {
        channelDropped.bump(type)
    }

    // ── Actors ────────────────────────────────────────────────────────────────

    class ActorStat {
        /** Messages accepted but not yet handled. */
        val depth = AtomicInteger(0)

        /** Messages handled since start. */
        val messages = AtomicLong(0)

        /** Handlers that exceeded the actor's slow-handler threshold. */
        val slow = AtomicLong(0)

        /** Handler exceptions, cancellations excluded. */
        val errors = AtomicLong(0)

        /** Total nanoseconds spent inside handlers, for a mean latency. */
        val handleNanos = AtomicLong(0)

        /** 1 while a handler is executing, 0 otherwise. */
        val inFlight = AtomicInteger(0)
    }

    private val actors = ConcurrentHashMap<String, ActorStat>()

    fun actor(name: String): ActorStat = actors.computeIfAbsent(name) { ActorStat() }

    fun forgetActor(name: String) {
        actors.remove(name)
    }

    // ── CDN attribution for media traffic ─────────────────────────────────────

    private val cdnServed = ConcurrentHashMap<String, AtomicLong>()
    private val cdnServeFailures = ConcurrentHashMap<String, AtomicLong>()

    /**
     * One segment actually fetched from [host] — the host whose rewritten URL served the bytes,
     * after any failover, not the host the selector first picked.
     *
     * Deliberately separate from `CdnSelector`'s `totalSuccesses`: that one also counts master
     * playlist fetches and skips sub-millisecond downloads, so it cannot answer "which CDN carried
     * the media traffic".
     */
    fun recordCdnServed(host: String, durationMs: Long) {
        cdnServed.bump(host)
        cdnSegmentDuration.computeIfAbsent(host) { DurationHistogram(SEGMENT_BUCKETS) }
            .observe(durationMs / 1000.0)
    }

    /**
     * Actual segment fetch time per host, as a histogram.
     *
     * `xhrec_cdn_estimated_duration_ms` is the *prediction* the selector ranks on, built from an
     * EWMA; this is what the downloads actually took. Without it there is no way to tell a host that
     * is predicted slow from one that is measured slow, or to notice that the estimate has drifted
     * away from reality.
     *
     * Buckets are chosen around the downloader's own budgets (`downloaderStallTimeout` 5s,
     * `downloaderAttemptTimeout` 25s), so a bucket boundary is where behaviour changes.
     */
    private val cdnSegmentDuration = ConcurrentHashMap<String, DurationHistogram>()

    /**
     * A segment this host failed to deliver (transport error, stall, 5xx). An expired assignment
     * (404/403) is not attributed to the host, matching the selector's own penalty rule.
     */
    fun recordCdnServeFailure(host: String) {
        cdnServeFailures.bump(host)
    }

    // ── Live event source ─────────────────────────────────────────────────────

    /** 1 while at least one WebSocket pool is connected. */
    @Volatile
    var wsConnected: Boolean = false
        private set

    val wsConnects = AtomicLong(0)
    val wsDisconnects = AtomicLong(0)

    fun recordWsConnected() {
        wsConnected = true
        wsConnects.incrementAndGet()
    }

    fun recordWsDisconnected() {
        wsConnected = false
        wsDisconnects.incrementAndGet()
    }

    // ── Prometheus rendering ──────────────────────────────────────────────────

    fun appendMetrics(sb: StringBuilder) {
        family(sb, "xhrec_eventbus_published_total", "Events published on the internal bus", "counter")
        eventsPublished.forEach { (type, count) ->
            sb.appendLine("xhrec_eventbus_published_total{event=\"$type\"} ${count.get()}")
        }

        family(sb, "xhrec_eventbus_dropped_total", "Events dropped because the bus buffer was full", "counter")
        eventsDropped.forEach { (type, count) ->
            sb.appendLine("xhrec_eventbus_dropped_total{event=\"$type\"} ${count.get()}")
        }

        family(sb, "xhrec_eventbus_backlogged", "The event bus is saturated and dropping (1) or not (0)", "gauge")
        sb.appendLine("xhrec_eventbus_backlogged ${if (eventBusBacklogged) 1 else 0}")

        family(sb, "xhrec_datachannel_sent_total", "Messages handed to the data channel", "counter")
        channelSent.forEach { (type, count) ->
            sb.appendLine("xhrec_datachannel_sent_total{msg=\"$type\"} ${count.get()}")
        }

        family(sb, "xhrec_datachannel_dropped_total", "Messages dropped because the data channel was full", "counter")
        channelDropped.forEach { (type, count) ->
            sb.appendLine("xhrec_datachannel_dropped_total{msg=\"$type\"} ${count.get()}")
        }

        family(sb, "xhrec_datachannel_pending", "Messages in the data channel, not yet written", "gauge")
        sb.appendLine("xhrec_datachannel_pending ${channelPending.get()}")

        family(sb, "xhrec_datachannel_bytes_total", "Media bytes handed to the data channel", "counter")
        sb.appendLine("xhrec_datachannel_bytes_total ${channelBytes.get()}")

        family(sb, "xhrec_actor_mailbox_depth", "Messages accepted by an actor but not yet handled", "gauge")
        actors.forEach { (actor, stat) ->
            sb.appendLine("xhrec_actor_mailbox_depth{actor=\"$actor\"} ${stat.depth.get()}")
        }

        family(sb, "xhrec_actor_messages_total", "Messages handled by an actor", "counter")
        actors.forEach { (actor, stat) ->
            sb.appendLine("xhrec_actor_messages_total{actor=\"$actor\"} ${stat.messages.get()}")
        }

        family(sb, "xhrec_actor_in_flight", "An actor is inside a handler (1) or idle (0)", "gauge")
        actors.forEach { (actor, stat) ->
            sb.appendLine("xhrec_actor_in_flight{actor=\"$actor\"} ${if (stat.inFlight.get() > 0) 1 else 0}")
        }

        family(sb, "xhrec_actor_slow_handlers_total", "Handlers that exceeded the actor's slow threshold", "counter")
        actors.forEach { (actor, stat) ->
            sb.appendLine("xhrec_actor_slow_handlers_total{actor=\"$actor\"} ${stat.slow.get()}")
        }

        family(sb, "xhrec_actor_errors_total", "Handler exceptions, cancellations excluded", "counter")
        actors.forEach { (actor, stat) ->
            sb.appendLine("xhrec_actor_errors_total{actor=\"$actor\"} ${stat.errors.get()}")
        }

        family(sb, "xhrec_actor_handle_seconds_total", "Total time spent inside actor handlers", "counter")
        actors.forEach { (actor, stat) ->
            sb.appendLine("xhrec_actor_handle_seconds_total{actor=\"$actor\"} ${stat.handleNanos.get() / 1_000_000_000.0}")
        }

        family(sb, "xhrec_cdn_segments_served_total", "Segments actually served by this CDN host", "counter")
        cdnServed.forEach { (host, count) ->
            sb.appendLine("xhrec_cdn_segments_served_total{host=\"$host\"} ${count.get()}")
        }

        family(sb, "xhrec_cdn_segment_failures_total", "Segments this CDN host failed to deliver", "counter")
        cdnServeFailures.forEach { (host, count) ->
            sb.appendLine("xhrec_cdn_segment_failures_total{host=\"$host\"} ${count.get()}")
        }

        if (cdnSegmentDuration.isNotEmpty()) {
            // Declared once: a repeated HELP/TYPE block makes strict scrapers reject the payload.
            family(sb, "xhrec_cdn_segment_duration_seconds", "Actual segment fetch time per CDN host", "histogram")
            cdnSegmentDuration.forEach { (host, histogram) ->
                histogram.appendSeries(sb, "xhrec_cdn_segment_duration_seconds", "host=\"$host\"")
            }
        }

        // Losing the WebSocket is not fatal but it is silent: room status changes stop arriving, so
        // rooms sit armed and unrecorded until the periodic refresh happens to notice.
        family(sb, "xhrec_ws_connected", "The live event WebSocket is connected (1) or not (0)", "gauge")
        sb.appendLine("xhrec_ws_connected ${if (wsConnected) 1 else 0}")

        family(sb, "xhrec_ws_connects_total", "Successful WebSocket connects", "counter")
        sb.appendLine("xhrec_ws_connects_total ${wsConnects.get()}")

        family(sb, "xhrec_ws_disconnects_total", "WebSocket disconnects", "counter")
        sb.appendLine("xhrec_ws_disconnects_total ${wsDisconnects.get()}")
    }

    /** One HELP/TYPE pair per family: repeating them inside the loop makes strict scrapers reject. */
    private fun family(sb: StringBuilder, name: String, help: String, type: String) {
        sb.appendLine("# HELP $name $help")
        sb.appendLine("# TYPE $name $type")
    }

    /**
     * Segment fetch buckets in seconds, placed around the downloader's own budgets
     * (`downloaderStallTimeout` 5s, `downloaderAttemptTimeout` 25s) so a boundary is where the
     * behaviour, not just the number, changes.
     */
    private val SEGMENT_BUCKETS = doubleArrayOf(0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 25.0)

    /**
     * Fixed-bucket latency histogram, rendered in the Prometheus text format.
     *
     * Counts are kept per bucket and cumulated only at render time: the scrape is the one place the
     * cumulative form is needed, and per-bucket counters let an observer touch a single slot.
     * `_sum` is accumulated in micros because an integer add stays exact under concurrency.
     */
    class DurationHistogram(private val boundsSeconds: DoubleArray) {
        private val counts = Array(boundsSeconds.size + 1) { AtomicLong(0) }
        private val observations = AtomicLong(0)
        private val sumMicros = AtomicLong(0)

        fun observe(seconds: Double) {
            val value = if (seconds.isFinite() && seconds >= 0) seconds else 0.0
            var index = boundsSeconds.size
            for (i in boundsSeconds.indices) {
                if (value <= boundsSeconds[i]) {
                    index = i
                    break
                }
            }
            counts[index].incrementAndGet()
            observations.incrementAndGet()
            sumMicros.addAndGet((value * 1_000_000).toLong())
        }

        /** Emits `_bucket` / `_sum` / `_count`; the family metadata belongs to the caller. */
        fun appendSeries(sb: StringBuilder, name: String, labels: String) {
            var cumulative = 0L
            for (i in boundsSeconds.indices) {
                cumulative += counts[i].get()
                sb.appendLine("${name}_bucket{$labels,le=\"${boundsSeconds[i]}\"} $cumulative")
            }
            cumulative += counts.last().get()
            sb.appendLine("${name}_bucket{$labels,le=\"+Inf\"} $cumulative")
            sb.appendLine("${name}_sum{$labels} ${sumMicros.get() / 1_000_000.0}")
            sb.appendLine("${name}_count{$labels} ${observations.get()}")
        }
    }

    private fun ConcurrentHashMap<String, AtomicLong>.bump(key: String) {
        computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
    }
}
