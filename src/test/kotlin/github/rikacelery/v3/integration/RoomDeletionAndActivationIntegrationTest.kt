package github.rikacelery.v3.integration

import github.rikacelery.v3.components.SessionState
import github.rikacelery.v3.events.EndReason
import github.rikacelery.v3.events.FileReady
import github.rikacelery.v3.events.RecordingStarted
import github.rikacelery.v3.events.SegmentDownloaded
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Room lifecycle as seen from the platform side:
 *
 * - a model deleted on the platform while it is recording must stop the session, close the
 *   file and disappear from the dashboard (no ghost row, no ever-growing size column);
 * - arming a room whose status is already recordable must start recording without waiting
 *   for a status change, and the same must hold for `/restart`.
 */
class RoomDeletionAndActivationIntegrationTest {

    @Test
    fun `platform deletion while recording stops the session and clears the dashboard`() = testApplication {
        XhrecIntegrationFixture(this).use { fx ->
            fx.start()
            fx.installRoutes()
            fx.ready()
            fx.mock.addRoom(1001, "model", status = "public")

            fx.get("/add?name=model&active=true").expectOk("Room added: model")
            fx.awaitSession(1001, SessionState.Recording)
            fx.awaitEvent<SegmentDownloaded>(15.seconds) { it.roomId == 1001L }

            // the platform now reports the model as deleted; the room poll auto-removes it
            fx.mock.markDeleted("model")

            val ready = fx.awaitEvent<FileReady>(20.seconds) { it.roomId == 1001L }
            assertTrue(ready.file.length() > 0, "the recording must be closed, not left growing")
            assertEquals(EndReason.UserStop, ready.reason)

            fx.awaitSession(1001, SessionState.Idle)
            fx.awaitRoomAbsent(1001)

            val dashboard = fx.dashboard()
            assertTrue(dashboard["rooms"]!!.jsonArray.isEmpty(), "no ghost room row")
            assertTrue(dashboard["listv2"]!!.jsonArray.isEmpty(), "no ghost dashboard row")
            assertTrue(dashboard["statuses"]!!.jsonObject.isEmpty(), "no stale size metrics")

            val mediaFetches = fx.mock.requests().count { it.path.startsWith("/media/1001/") }
            delay(700)
            assertEquals(
                mediaFetches,
                fx.mock.requests().count { it.path.startsWith("/media/1001/") },
                "a deleted room must not keep fetching segments"
            )
        }
    }

    @Test
    fun `re-adding a deleted room records a fresh file with no leftover bytes`() = testApplication {
        XhrecIntegrationFixture(this).use { fx ->
            fx.start()
            fx.installRoutes()
            fx.ready()
            val room = fx.mock.addRoom(1001, "model", status = "public")

            // first episode: init + two segments, advanced one at a time for a known size
            fx.get("/add?name=model&active=true").expectOk("Room added: model")
            fx.awaitSession(1001, SessionState.Recording)
            fx.awaitEventCount<SegmentDownloaded>(1, 15.seconds) { it.roomId == 1001L }
            repeat(2) { index ->
                room.advanceSegment()
                fx.awaitEventCount<SegmentDownloaded>(index + 2, 20.seconds) { it.roomId == 1001L }
            }

            fx.mock.markDeleted("model")
            val firstFile = fx.awaitFile(1001)
            assertEquals(
                MockPayloads.INIT_SIZE.toLong() + 2L * MockPayloads.SEGMENT_SIZE,
                firstFile.length(),
                "first episode must be init + 2 segments"
            )
            fx.awaitRoomAbsent(1001)

            // the model is back; re-adding must behave like a brand-new room
            fx.mock.markDeleted("model", deleted = false)
            fx.get("/add?name=model&active=true").expectOk("Room added: model")
            fx.awaitEventCount<RecordingStarted>(2, 20.seconds) { it.roomId == 1001L }

            // the new session re-downloads the current sliding window (init + 2 segments)
            fx.awaitEventCount<SegmentDownloaded>(6, 20.seconds) { it.roomId == 1001L }
            delay(300)

            fx.get("/remove?id=1001").expectOk("Removed")
            val files = fx.awaitEventCount<FileReady>(2, 20.seconds) { it.roomId == 1001L }
            val second = files[1].file.readBytes()

            assertEquals(
                MockPayloads.INIT_SIZE + 2 * MockPayloads.SEGMENT_SIZE,
                second.size,
                "the new file must contain only the new episode, not the deleted room's bytes"
            )
            assertContentEquals(
                MockPayloads.initBytes(1001, room.generation),
                second.copyOfRange(0, MockPayloads.INIT_SIZE),
                "the new file must start with the current init segment"
            )
        }
    }

    @Test
    fun `activating an already public room records immediately`() = testApplication {
        XhrecIntegrationFixture(this).use { fx ->
            fx.start()
            fx.installRoutes()
            fx.ready()
            fx.mock.addRoom(1001, "model", status = "public")

            fx.get("/add?name=model&active=false").expectOk("Room added: model")
            // the room poll learns the status first, so activation sees no status transition
            fx.awaitRoom("model") { !it.boolField("listening") && it.path("room.status") == "public" }
            assertTrue(fx.sessions().isEmpty(), "inactive room must not record")

            // no status change and no WebSocket push: activation alone must start recording
            fx.get("/activate?id=1001").expectOk("Activated")
            fx.awaitSession(1001, SessionState.Recording, timeout = 15.seconds)
            fx.awaitEvent<SegmentDownloaded>(15.seconds) { it.roomId == 1001L }
        }
    }

    @Test
    fun `restarting an already public room records again immediately`() = testApplication {
        XhrecIntegrationFixture(this).use { fx ->
            fx.start()
            fx.installRoutes()
            fx.ready()
            fx.mock.addRoom(1001, "model", status = "public")

            fx.get("/add?name=model&active=true").expectOk("Room added: model")
            fx.awaitSession(1001, SessionState.Recording)
            fx.awaitEvent<SegmentDownloaded>(15.seconds) { it.roomId == 1001L }
            val episodes = fx.events.filterIsInstance<RecordingStarted>().count { it.roomId == 1001L }

            fx.get("/restart?id=1001").expectOk("Restarted")

            // the restart re-arms the room while it is still public; it must record again
            fx.awaitEventCount<RecordingStarted>(episodes + 1, 20.seconds) { it.roomId == 1001L }
        }
    }
}
