package github.rikacelery.v3.integration

import github.rikacelery.v3.events.LiveMessage
import github.rikacelery.v3.events.RecordingStarted
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end guard for the `.event` sidecar: a platform frame published on the bus has to survive
 * the whole production path — recorder → `DataChannel` → `WriterComponent` → file on disk.
 *
 * The unit tests cover the recorder in isolation; this one exists because the failure mode that
 * started all this was a *wiring* gap, which no isolated test can see. It asserts on the bytes in
 * the file rather than on an event having been published, so a recorder that is constructed but
 * never started (or never registered in the component list) fails here.
 */
class EventSidecarIntegrationTest {

    @Test
    fun `a platform frame reaches the event sidecar on disk`() = withFixture { fx ->
        fx.ready()
        fx.startRecording()
        // The room only accepts events once RecordingStarted has been handled, and startRecording
        // has already awaited a SegmentDownloaded, which the session publishes after it.
        fx.awaitEvent<RecordingStarted>(10.seconds) { it.roomId == 1001L }

        fx.eventBus.publish(
            LiveMessage(
                1001L,
                "newChatMessage",
                Json.parseToJsonElement(
                    """
                    {"message":{"type":"tip","createdAt":"2026-05-11T04:23:03Z",
                     "details":{"amount":35,"body":"Harder and Deeper","source":"tipMenu"}}}
                    """.trimIndent()
                ).jsonObject
            )
        )

        val sidecar = fx.awaitEventSidecar("createdAt")
        val line = sidecar.readLines().first { it.contains("createdAt") }
        val parsed = Json.parseToJsonElement(line).jsonObject

        // The cutter's EventParser resolves the payload from `data` and picks the channel off `type`.
        assertTrue(parsed.containsKey("data"), "payload must stay under `data`: $line")
        val channel = parsed["type"]?.jsonPrimitive?.content
        assertTrue(channel != null && channel.isNotBlank(), "channel type must be recorded: $line")
        val message = parsed["data"]!!.jsonObject["message"]!!.jsonObject
        assertTrue(
            message["createdAt"]?.jsonPrimitive?.content == "2026-05-11T04:23:03Z",
            "the platform's own occurrence timestamp must survive verbatim: $line"
        )
    }
}

/**
 * Waits for the open recording's `.event` sidecar to contain [needle].
 *
 * The sidecar is written on the writer's IO dispatcher, so the bytes are not there the instant
 * `publish` returns — hence the poll. Reading the file the writer is appending to is fine: the line
 * is written whole, and this only ever looks for a line that is already complete.
 */
private suspend fun XhrecIntegrationFixture.awaitEventSidecar(
    needle: String,
    timeout: kotlin.time.Duration = 10.seconds
): File {
    val found = withTimeoutOrNull(timeout) {
        while (true) {
            val hit = tmpDir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".event") }
                .firstOrNull { runCatching { it.readText().contains(needle) }.getOrDefault(false) }
            if (hit != null) return@withTimeoutOrNull hit
            delay(25)
        }
        @Suppress("UNREACHABLE_CODE") null
    }
    assertNotNull(found, "no .event sidecar containing '$needle' under ${tmpDir.absolutePath}")
    return found
}
