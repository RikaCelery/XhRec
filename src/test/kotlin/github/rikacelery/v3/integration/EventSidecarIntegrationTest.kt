package github.rikacelery.v3.integration

import github.rikacelery.v3.events.EndReason
import github.rikacelery.v3.events.LiveMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The `.event` sidecar, end to end: a platform frame published on the bus has to survive the
 * whole production path and land in the file belonging to the room's *currently open*
 * recording.
 *
 * The gate is `WriterComponent`'s own open-file map, which is the point of these tests. A
 * recorder that decided for itself whether the room was recording could not answer this
 * question correctly around a cut: `StreamEnd` closes the sidecar and the next `StreamStart`
 * opens a new one, while the room is "recording" the whole time, so events emitted in
 * between had nowhere to go and vanished with nothing in the log.
 */
class EventSidecarIntegrationTest {

    @Test
    fun `a platform frame reaches the event sidecar on disk`() = withFixture { fx ->
        fx.ready()
        fx.startRecording()

        fx.eventBus.publish(LiveMessage(1001L, "newChatMessage", tipBody()))

        val sidecar = fx.awaitEventSidecar("createdAt")
        assertNotNull(sidecar, "no .event sidecar was written for the open recording")
        val line = sidecar.readLines().first { it.contains("createdAt") }
        val parsed = Json.parseToJsonElement(line).jsonObject

        // The reader resolves the payload from `data` and takes the channel from `type`.
        assertTrue(parsed.containsKey("data"), "payload must stay under `data`: $line")
        val channel = parsed["type"]?.jsonPrimitive?.content
        assertTrue(channel != null && channel.isNotBlank(), "channel type must be recorded: $line")
        val message = parsed["data"]!!.jsonObject["message"]!!.jsonObject
        assertEquals(
            "2026-05-11T04:23:03Z",
            message["createdAt"]?.jsonPrimitive?.content,
            "the platform's own occurrence timestamp must survive verbatim: $line"
        )

        // Receipt time is a sibling of `data`, never inside it: the reader's occurrence-time
        // whitelist must not be able to pick it up.
        assertTrue(parsed.containsKey("recordedAt"))
        assertTrue(!message.containsKey("recordedAt"))
    }

    /**
     * The regression that motivated moving the gate: while a recording is closed — between a
     * cut's `StreamEnd` and the next `StreamStart` — there is no sidecar to append to, so the
     * frame must not be written anywhere. It used to be emitted onto the data channel and
     * silently discarded by the writer, which meant the event was lost even though the room
     * still counted as recording.
     */
    @Test
    fun `events published while the recording is closed are not written to the next file`() = withFixture { fx ->
        fx.ready()
        fx.startRecording()
        fx.eventBus.publish(LiveMessage(1001L, "newChatMessage", tipBody()))
        assertNotNull(fx.awaitEventSidecar("createdAt"), "precondition: the open file accepts events")

        // Close the recording, then emit into the gap. The room is still 'recording' from the
        // session's point of view; only the writer knows the handle is gone.
        fx.get("/break?id=1001")
        fx.awaitEvent<github.rikacelery.v3.events.FileReady>(15.seconds) { it.roomId == 1001L }

        val orphan = "ORPHAN-${System.nanoTime()}"
        fx.eventBus.publish(LiveMessage(1001L, "newChatMessage", textBody(orphan)))
        delay(500)

        // Whatever sidecars exist, none of them may contain the event emitted while closed.
        val leaked = fx.tmpDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".event") }
            .filter { runCatching { it.readText().contains(orphan) }.getOrDefault(false) }
            .toList()
        assertTrue(leaked.isEmpty(), "an event emitted with no open file was written to ${leaked.map { it.name }}")
    }
}

private fun tipBody() = Json.parseToJsonElement(
    """
    {"message":{"type":"tip","createdAt":"2026-05-11T04:23:03Z",
     "details":{"amount":35,"body":"Harder and Deeper","source":"tipMenu"}}}
    """.trimIndent()
).jsonObject

private fun textBody(marker: String) = buildJsonObject {
    put("message", buildJsonObject {
        put("type", "text")
        put("createdAt", "2026-05-11T04:23:04Z")
        put("details", buildJsonObject { put("body", marker) })
    })
}

/**
 * Waits for the open recording's `.event` sidecar to contain [needle].
 *
 * The line is written on the writer's IO dispatcher, so the bytes are not there the instant
 * `publish` returns — hence the poll. Only ever looks for a line that is already complete.
 */
private suspend fun XhrecIntegrationFixture.awaitEventSidecar(
    needle: String,
    timeout: kotlin.time.Duration = 10.seconds
): File? {
    return withTimeoutOrNull(timeout) {
        while (true) {
            val hit = tmpDir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".event") }
                .firstOrNull { runCatching { it.readText().contains(needle) }.getOrDefault(false) }
            if (hit != null) return@withTimeoutOrNull hit
            delay(25)
        }
        @Suppress("UNREACHABLE_CODE") null
    }
}
