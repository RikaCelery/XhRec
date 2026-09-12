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
    }

    /** One HELP/TYPE pair per family: repeating them inside the loop makes strict scrapers reject. */
    private fun family(sb: StringBuilder, name: String, help: String, type: String) {
        sb.appendLine("# HELP $name $help")
        sb.appendLine("# TYPE $name $type")
    }

    private fun ConcurrentHashMap<String, AtomicLong>.bump(key: String) {
        computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
    }
}
