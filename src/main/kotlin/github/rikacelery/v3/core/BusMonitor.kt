package github.rikacelery.v3.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Live tap on the three internal transports, feeding the dashboard's debug stream.
 *
 * The taps are **off by default and cost nothing while nobody watches**: every producer checks
 * [wants] before it builds an event, so an unwatched recorder never pays for serialization.
 * A watcher declares the categories it cares about, and emissions go through a
 * [MutableSharedFlow] with `DROP_OLDEST`, so a slow HTTP client can never stall a bus, a
 * download or an actor — it just loses the oldest lines of a debug stream.
 *
 * Categories:
 *  - `request`  — a [RequestBus] round trip (command, outcome, latency)
 *  - `data`     — a [DataChannel] message (stream start/data/end/event)
 *  - `event`    — an [EventBus] publish
 */
object BusMonitor {

    const val REQUEST = "request"
    const val DATA = "data"
    const val EVENT = "event"

    /** Every category a client may ask for. */
    val CATEGORIES = listOf(REQUEST, DATA, EVENT)

    private val _events = MutableSharedFlow<JsonObject>(
        replay = 0,
        extraBufferCapacity = 2048,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** The wire feed: one [JsonObject] per line for every watched message. */
    val events: SharedFlow<JsonObject> = _events.asSharedFlow()

    /** Watchers per category; a category is produced only while at least one client wants it. */
    private val watchers = ConcurrentHashMap<String, AtomicInteger>()

    /** Lines dropped because no subscriber kept up. Surfaced to the client so it knows to narrow. */
    val dropped = AtomicLong(0)

    private val sequence = AtomicLong(0)

    /** Registers one watcher for each of [types]; pair with [unwatch]. */
    fun watch(types: Collection<String>) {
        types.forEach { watchers.computeIfAbsent(it) { AtomicInteger() }.incrementAndGet() }
    }

    /** Removes the watcher registered by a matching [watch] call. */
    fun unwatch(types: Collection<String>) {
        types.forEach { watchers[it]?.decrementAndGet() }
    }

    /** True when at least one client is watching [type], i.e. the tap should build its event. */
    fun wants(type: String): Boolean {
        val counter = watchers[type] ?: return false
        return counter.get() > 0
    }

    /** Emits one monitored line. Never suspends and never throws: debug must not break the pipeline. */
    fun record(type: String, body: JsonObject) {
        if (!wants(type)) return
        val line = buildJsonObject {
            put("seq", sequence.incrementAndGet())
            put("ts", System.currentTimeMillis())
            put("kind", type)
            body.forEach { (k, v) -> put(k, v) }
        }
        if (!_events.tryEmit(line)) dropped.incrementAndGet()
    }

    /** Truncates a value for the wire so one huge payload cannot flood the stream. */
    fun shorten(value: Any?, max: Int = 300): String {
        val text = value?.toString() ?: "null"
        return if (text.length <= max) text else text.take(max) + "…"
    }
}
