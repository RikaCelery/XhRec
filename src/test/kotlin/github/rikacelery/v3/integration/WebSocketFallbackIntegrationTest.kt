package github.rikacelery.v3.integration

import github.rikacelery.v3.components.SessionState
import github.rikacelery.v3.data.Hosts
import github.rikacelery.v3.utils.CdnSelector
import github.rikacelery.v3.events.EndReason
import github.rikacelery.v3.events.FileReady
import github.rikacelery.v3.events.RecordingStarted
import github.rikacelery.v3.events.RoomStatusChanged
import github.rikacelery.v3.events.SegmentDownloaded
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * WebSocket delivery is the fast path for room status; HTTP polling is the fallback and the
 * recovery path. Both must drive the real Scheduler/Session actors without duplicates.
 */
class WebSocketFallbackIntegrationTest {

    @Test
    fun `websocket push starts recording while polling is idle`() = testApplication {
        val tuning = XhrecIntegrationFixture.testTuning(roomPollInterval = 30.seconds)
        withFixture(tuning) { fx ->
            fx.ready()
            fx.mock.addRoom(1001, "model", status = "off")

            fx.get("/add?name=model").expectOk("Room added: model")
            fx.get("/activate?id=1001").expectOk("Activated")
            fx.mock.startSegments(1001, 40.milliseconds)
            fx.awaitRoomSubscribed(1001)
            assertTrue(fx.sessions().isEmpty(), "offline room must not record yet")

            // the only signal is the WebSocket frame; the 30s poll cannot have fired
            fx.mock.setRoomStatus(1001, "public")

            fx.awaitSession(1001, SessionState.Recording, timeout = 10.seconds)
            fx.awaitEvent<SegmentDownloaded>(10.seconds) { it.roomId == 1001L }
        }
    }

    @Test
    fun `nested model status and stream status frames drive the session`() = testApplication {
        val tuning = XhrecIntegrationFixture.testTuning(roomPollInterval = 30.seconds)
        withFixture(tuning) { fx ->
            fx.ready()
            fx.mock.addRoom(1001, "model", status = "off")

            fx.get("/add?name=model").expectOk("Room added: model")
            fx.get("/activate?id=1001").expectOk("Activated")
            fx.mock.startSegments(1001, 40.milliseconds)
            fx.awaitRoomSubscribed(1001)

            // the mock's HTTP view and the frame must agree; only the frame is pushed
            fx.mock.setRoomStatus(1001, "public", push = false)
            // status nested under "model" instead of top-level
            fx.mock.push(
                "modelStatusChanged@1001",
                buildJsonObject { put("model", buildJsonObject { put("status", "public") }) }
            )
            fx.awaitSession(1001, SessionState.Recording, timeout = 10.seconds)
            fx.awaitEvent<SegmentDownloaded>(10.seconds) { it.roomId == 1001L }

            // stream lifecycle is a separate domain and ends the recording
            fx.mock.push("streamChanged@1001", buildJsonObject { put("status", "finished") })
            val ready = fx.awaitEvent<FileReady>(10.seconds) { it.roomId == 1001L }
            assertEquals(EndReason.StreamEnd, ready.reason)
        }
    }

    @Test
    fun `duplicate status frames are suppressed`() = testApplication {
        val tuning = XhrecIntegrationFixture.testTuning(roomPollInterval = 30.seconds)
        withFixture(tuning) { fx ->
            fx.ready()
            fx.mock.addRoom(1001, "model", status = "off")

            fx.get("/add?name=model").expectOk("Room added: model")
            fx.get("/activate?id=1001").expectOk("Activated")
            fx.awaitRoomSubscribed(1001)

            repeat(3) { fx.mock.setRoomStatus(1001, "public") }
            delay(500)

            assertEquals(
                1,
                fx.events.filterIsInstance<RoomStatusChanged>().count { it.roomId == 1001L && it.newStatus == "public" },
                "repeated identical frames must not republish the status"
            )
        }
    }

    @Test
    fun `malformed frame does not block the next valid frame`() = testApplication {
        val tuning = XhrecIntegrationFixture.testTuning(roomPollInterval = 30.seconds)
        withFixture(tuning) { fx ->
            fx.ready()
            fx.mock.addRoom(1001, "model", status = "off")

            fx.get("/add?name=model").expectOk("Room added: model")
            fx.get("/activate?id=1001").expectOk("Activated")
            fx.mock.startSegments(1001, 40.milliseconds)
            fx.awaitRoomSubscribed(1001)

            fx.mock.setRoomStatus(1001, "public", push = false)
            val valid = buildJsonObject {
                put("push", buildJsonObject {
                    put("channel", "broadcastChanged@1001")
                    put("pub", buildJsonObject { put("data", buildJsonObject { put("status", "public") }) })
                })
            }
            fx.mock.pushRaw("broadcastChanged@1001", "{\"push\":{\"channel\":\"broadcastChanged@1001\"" + "\n" + valid)

            fx.awaitSession(1001, SessionState.Recording, timeout = 10.seconds)
        }
    }

    @Test
    fun `subscription set expands while recording and shrinks when removed`() = testApplication {
        withFixture { fx ->
            fx.ready()
            fx.mock.addRoom(1001, "model", status = "off")

            fx.get("/add?name=model").expectOk("Room added: model")
            fx.awaitRoomSubscribed(1001)
            val idle = fx.mock.subscribedChannels().filter { it.endsWith("@1001") }.toSet()
            assertTrue("broadcastChanged@1001" in idle, "tracked rooms need status channels: $idle")
            assertTrue("newChatMessage@1001" !in idle, "idle rooms must not hold the full set: $idle")

            fx.get("/activate?id=1001").expectOk("Activated")
            fx.mock.setRoomStatus(1001, "public")
            fx.awaitSession(1001, SessionState.Recording)
            assertTrue(
                fx.mock.awaitSubscription("newChatMessage@1001", 10.seconds),
                "recording rooms need the full channel set: ${fx.mock.subscribedChannels()}"
            )

            fx.get("/remove?id=1001").expectOk("Removed")
            assertTrue(
                fx.mock.awaitSubscriptionGone("broadcastChanged@1001", 10.seconds),
                "removed rooms must be unsubscribed: ${fx.mock.subscribedChannels()}"
            )
            assertTrue(fx.mock.awaitSubscriptionGone("newChatMessage@1001", 10.seconds))
        }
    }

    @Test
    fun `all platform traffic stays on the loopback mock`() = testApplication {
        withFixture { fx ->
            fx.ready()
            fx.startRecording()

            assertTrue(fx.mock.requests().isNotEmpty(), "the fixture must have talked to the mock")
            assertTrue(
                fx.mock.requests().all { it.path.startsWith("/") && !it.path.startsWith("//") },
                "requests must be relative paths on the mock: ${fx.mock.requests().map { it.path }}"
            )
            assertEquals(listOf(fx.mock.host), Hosts.current.platformHosts)
            assertEquals(listOf(fx.mock.host), Hosts.current.webSocketHosts)
            assertTrue(CdnSelector.hosts.isEmpty(), "CDN rewriting must be disabled: ${CdnSelector.hosts}")
            assertTrue(fx.mock.requests().any { it.path.startsWith("/hls/1001/") })
        }
    }

    @Test
    fun `rejected websocket falls back to http polling`() = testApplication {
        val tuning = XhrecIntegrationFixture.testTuning(roomPollInterval = 300.milliseconds)
        withFixture(tuning) { fx ->
            fx.ready()
            fx.mock.addRoom(1001, "model", status = "off")
            fx.mock.rejectWebSockets(true)

            fx.get("/add?name=model").expectOk("Room added: model")
            fx.get("/activate?id=1001").expectOk("Activated")
            fx.mock.startSegments(1001, 40.milliseconds)

            // With WebSockets rejected, HTTP status polling is the only way the room can learn about
            // the status change below. Wait for a poll *after* activation (its own refresh already
            // completed before this snapshot) instead of sleeping a fixed interval.
            val pollsBefore = fx.mock.requests().count { it.path.contains("broadcasts") }
            fx.awaitRequestCount("broadcasts", pollsBefore + 1)

            // HTTP status changes without any WebSocket push
            fx.mock.setRoomStatus(1001, "public", push = false)

            fx.awaitSession(1001, SessionState.Recording, timeout = 20.seconds)
            assertTrue(
                fx.mock.pushes().none { it.channel == "broadcastChanged@1001" },
                "no push may have been delivered: ${fx.mock.pushes()}"
            )
        }
    }

    @Test
    fun `recovery resumes pushes after a reconnect catch-up`() = testApplication {
        val tuning = XhrecIntegrationFixture.testTuning(roomPollInterval = 30.seconds)
        withFixture(tuning) { fx ->
            fx.ready()
            fx.mock.addRoom(1001, "model", status = "off")
            fx.mock.rejectWebSockets(true)

            fx.get("/add?name=model").expectOk("Room added: model")
            fx.get("/activate?id=1001").expectOk("Activated")
            fx.mock.startSegments(1001, 40.milliseconds)

            // polling is off (30s) and the WebSocket is rejected, so nothing records yet
            delay(1000)
            assertTrue(fx.sessions().isEmpty(), "no transport is available yet")

            // the status changes while the socket is down; only the reconnect catch-up can see it
            fx.mock.setRoomStatus(1001, "public", push = false)
            fx.mock.rejectWebSockets(false)
            assertTrue(fx.mock.awaitSubscription("broadcastChanged@1001", 20.seconds))

            fx.awaitSession(1001, SessionState.Recording, timeout = 20.seconds)
            // let the recovered session actually record a segment, so the stop closes a non-empty file
            fx.awaitEvent<SegmentDownloaded>(15.seconds) { it.roomId == 1001L }

            // pushes resume and are not duplicated
            fx.mock.setRoomStatus(1001, "off")
            val ready = fx.awaitEvent<FileReady>(20.seconds) { it.roomId == 1001L }
            assertEquals(EndReason.StreamEnd, ready.reason)
            assertEquals(
                1,
                fx.events.filterIsInstance<RecordingStarted>().count { it.roomId == 1001L },
                "recovery must not start a second recording episode"
            )
        }
    }
}
