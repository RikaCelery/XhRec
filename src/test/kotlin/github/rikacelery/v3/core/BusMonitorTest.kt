package github.rikacelery.v3.core

import github.rikacelery.v3.data.DownloadMeta
import github.rikacelery.v3.data.StreamData
import github.rikacelery.v3.data.StreamStart
import github.rikacelery.v3.events.CommandAck
import github.rikacelery.v3.events.CommandEnvelope
import github.rikacelery.v3.events.GetMaskStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The debug taps are only armed while a client is watching, so the recorder pays nothing for them
 * in normal operation. These tests pin both halves of that contract: silent when unwatched, and
 * actually wired to the request bus and the data channel when watched.
 *
 * Emissions are delivered asynchronously through the shared flow, so every assertion waits for its
 * line instead of assuming the collector already ran.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BusMonitorTest {

    @Test
    fun `a category is silent until somebody watches it`() {
        assertFalse(BusMonitor.wants(BusMonitor.REQUEST))
        BusMonitor.record(BusMonitor.REQUEST, buildJsonObject { put("ignored", true) })
        assertFalse(BusMonitor.wants(BusMonitor.REQUEST))
    }

    @Test
    fun `watch arms only the requested category and unwatch disarms it`() {
        try {
            BusMonitor.watch(listOf(BusMonitor.REQUEST))
            assertTrue(BusMonitor.wants(BusMonitor.REQUEST))
            assertFalse(BusMonitor.wants(BusMonitor.DATA), "unrequested categories stay silent")
        } finally {
            BusMonitor.unwatch(listOf(BusMonitor.REQUEST))
        }
        assertFalse(BusMonitor.wants(BusMonitor.REQUEST))
    }

    @Test
    fun `a watched request round trip is tapped with its outcome and latency`() = runTest {
        val eventBus = EventBus()
        val requestBus = RequestBus(eventBus, backgroundScope)
        eventBus.subscribe(backgroundScope, CommandEnvelope::class) { env ->
            eventBus.publish(CommandAck(env.id, "ok"))
        }
        assertTrue(requestBus.awaitSubscribed())

        val lines = collectWatched(backgroundScope, listOf(BusMonitor.REQUEST))
        try {
            val result: String = requestBus.request(GetMaskStatus)
            assertEquals("ok", result)

            val line = lines.await("the tapped request") { it["kind"]?.jsonPrimitive?.content == "request" }
            assertEquals("GetMaskStatus", line["cmd"]?.jsonPrimitive?.content)
            assertEquals("ok", line["result"]?.jsonPrimitive?.content)
            assertTrue((line["ms"]?.jsonPrimitive?.content?.toLong() ?: -1) >= 0, "latency is reported: $line")
        } finally {
            BusMonitor.unwatch(listOf(BusMonitor.REQUEST))
        }
    }

    @Test
    fun `a watched data channel message is tapped with its size`() = runTest {
        val channel = DataChannel()
        val lines = collectWatched(backgroundScope, listOf(BusMonitor.DATA))
        try {
            channel.send(StreamStart(7, "model", Instant.now(), "720p"))
            channel.send(
                StreamData(
                    7, ByteArray(16), 1,
                    DownloadMeta("https://media.example/seg.mp4", 5, proxied = false, timestamp = Instant.now())
                )
            )

            val start = lines.await("StreamStart") { it["msg"]?.jsonPrimitive?.content == "StreamStart" }
            assertEquals("7", start["room"]?.jsonPrimitive?.content)

            val data = lines.await("StreamData") { it["msg"]?.jsonPrimitive?.content == "StreamData" }
            assertEquals("16", data["bytes"]?.jsonPrimitive?.content)
        } finally {
            BusMonitor.unwatch(listOf(BusMonitor.DATA))
        }
    }

    /**
     * Attaches a collector and waits for the subscription to be live before arming the tap, so the
     * first emission cannot be missed by a collector that has not attached yet.
     */
    private suspend fun collectWatched(scope: CoroutineScope, types: List<String>): CapturedLines {
        val lines = CapturedLines()
        val attached = CompletableDeferred<Unit>()
        scope.launch { BusMonitor.events.onSubscription { attached.complete(Unit) }.collect { lines += it } }
        attached.await()
        BusMonitor.watch(types)
        return lines
    }

    /** Lines captured from the shared flow, with a bounded wait that yields to the collector. */
    private class CapturedLines {
        private val lines = CopyOnWriteArrayList<JsonObject>()

        operator fun plusAssign(line: JsonObject) {
            lines += line
        }

        suspend fun await(what: String, predicate: (JsonObject) -> Boolean): JsonObject {
            val found = withTimeoutOrNull(5.seconds) {
                var hit: JsonObject? = null
                while (hit == null) {
                    hit = lines.firstOrNull(predicate)
                    if (hit == null) delay(2)
                }
                hit
            }
            return found ?: error("timed out waiting for $what; captured=${lines.toList()}")
        }
    }
}
