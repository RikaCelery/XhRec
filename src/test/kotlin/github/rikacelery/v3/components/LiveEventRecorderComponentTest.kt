package github.rikacelery.v3.components

import github.rikacelery.v3.core.DataChannel
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.data.StreamEvent
import github.rikacelery.v3.events.LiveMessage
import github.rikacelery.v3.events.RecordingStarted
import github.rikacelery.v3.events.RecordingStopped
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `.event` sidecar is what every chat/tip/toy lane in the cutter is built from, and the way it
 * breaks is silent: `9457976` left [LiveMessage] with no subscriber and [StreamEvent] with no
 * construction site, so recordings kept succeeding while every sidecar came out empty and was then
 * deleted by `WriterComponent`. Nothing threw and nothing was logged, so no existing test noticed.
 *
 * These tests pin the two halves that can regress independently: that a platform frame becomes a
 * data-channel [StreamEvent] at all, and that the line it carries is still the envelope the cutter's
 * `EventParser` unwraps on the reading side.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveEventRecorderComponentTest {

    private val json = Json

    @Test
    fun `a platform frame for a recording room becomes a stream event`() = runTest(UnconfinedTestDispatcher()) {
        val (bus, channel, recorder) = recorder()

        bus.publish(RecordingStarted(42L, "720p"))
        bus.publish(LiveMessage(42L, "newChatMessage", chatBody("lovense")))

        val sent = channel.receive() as StreamEvent
        recorder.stop()

        assertEquals(42L, sent.roomId, "the event must be attributed to its own room")
        val line = json.parseToJsonElement(sent.eventJson).jsonObject
        assertEquals("newChatMessage", line["type"]?.jsonPrimitive?.content)
    }

    /**
     * The reading side is `EventParser.unwrap`, which resolves the payload from `data` and switches
     * on `type` for the channel. Writing a bare payload instead would still be valid JSON and would
     * still produce a non-empty file — it would just classify every event as `unknown` — so the
     * shape is asserted, not merely the fact that a line was written.
     */
    @Test
    fun `the line keeps the envelope the cutter parses`() = runTest(UnconfinedTestDispatcher()) {
        val (bus, channel, recorder) = recorder()

        bus.publish(RecordingStarted(7L))
        bus.publish(
            LiveMessage(
                7L,
                "newChatMessage",
                buildJsonObject {
                    put("message", buildJsonObject {
                        put("type", "tip")
                        put("createdAt", "2026-05-11T04:23:03Z")
                        put("details", buildJsonObject { put("amount", 35) })
                    })
                }
            )
        )

        val line = json.parseToJsonElement((channel.receive() as StreamEvent).eventJson).jsonObject
        recorder.stop()

        // `LiveEventSource.dispatch` strips the "@roomId" suffix before publishing, so `type` is
        // exactly the key `EventParser.classify` switches on.
        assertEquals("newChatMessage", line["type"]?.jsonPrimitive?.content)

        val data = line["data"] as? JsonObject
        assertNotNull(data, "the platform payload must sit under `data` for EventParser.unwrap")
        val message = data["message"] as? JsonObject
        assertNotNull(message)
        assertEquals("tip", message["type"]?.jsonPrimitive?.content)
        assertEquals("2026-05-11T04:23:03Z", message["createdAt"]?.jsonPrimitive?.content)

        // Receipt time is a sibling of `data`, never a field inside it: EventParser's timestamp
        // whitelist must not be able to pick it up and read it as the platform's occurrence time.
        assertTrue(line.containsKey("recordedAt"), "receipt time is kept for log correlation")
        assertTrue(!message.containsKey("recordedAt"), "receipt time must not shadow createdAt")
        assertNull(
            message["details"]?.jsonObject?.get("recordedAt"),
            "receipt time must not be merged into the payload either"
        )
    }

    @Test
    fun `events for a room that is not recording are not persisted`() = runTest(UnconfinedTestDispatcher()) {
        val (bus, channel, recorder) = recorder()

        // Only 42 starts recording; 99 is a tracked-but-idle room whose channels still deliver.
        bus.publish(RecordingStarted(42L))
        bus.publish(LiveMessage(99L, "newChatMessage", chatBody("text")))
        bus.publish(LiveMessage(42L, "groupShow", buildJsonObject { put("state", "public") }))

        val sent = channel.receive() as StreamEvent
        recorder.stop()

        assertEquals(42L, sent.roomId, "only the recording room may open or feed a sidecar")
        assertNull(
            withTimeoutOrNull(100) { channel.receive() },
            "the idle room's frame must not have been written"
        )
    }

    @Test
    fun `recording stopped closes the room to further events`() = runTest(UnconfinedTestDispatcher()) {
        val (bus, channel, recorder) = recorder()

        bus.publish(RecordingStarted(42L))
        bus.publish(LiveMessage(42L, "newChatMessage", chatBody("text")))
        val beforeStop = channel.receive() as StreamEvent

        bus.publish(RecordingStopped(42L))
        // A frame that races the stop must not land in whatever file is opened next.
        bus.publish(LiveMessage(42L, "newChatMessage", chatBody("text")))
        recorder.stop()

        assertEquals(42L, beforeStop.roomId)
        assertNull(
            withTimeoutOrNull(100) { channel.receive() },
            "no event may be written after the room stops recording"
        )
    }
}

private fun chatBody(messageType: String) = buildJsonObject {
    put("message", buildJsonObject { put("type", messageType) })
}

/** Starts a recorder against a fresh bus/channel, attached before any test publishes. */
private suspend fun TestScope.recorder(): Triple<EventBus, DataChannel, LiveEventRecorderComponent> {
    val bus = EventBus()
    val channel = DataChannel()
    val recorder = LiveEventRecorderComponent(channel, bus, backgroundScope)
    recorder.start()
    check(recorder.awaitSubscribed()) { "recorder did not subscribe to the event bus" }
    return Triple(bus, channel, recorder)
}
