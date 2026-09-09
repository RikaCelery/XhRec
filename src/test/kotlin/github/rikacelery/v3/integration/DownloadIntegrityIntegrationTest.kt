package github.rikacelery.v3.integration

import github.rikacelery.v3.components.SessionState
import github.rikacelery.v3.events.FileReady
import github.rikacelery.v3.events.SegmentDownloaded
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Byte-exact integrity of the recording pipeline: sliding-window dedupe, init changes,
 * permanent (404) vs transient (5xx) failures, and stall recovery all end in a file that
 * contains exactly the expected init+segment bytes in order.
 */
class DownloadIntegrityIntegrationTest {

    @Test
    fun `sliding window overlaps are downloaded exactly once`() = testApplication {
        XhrecIntegrationFixture(this).use { fx ->
            fx.start()
            fx.installRoutes()
            fx.ready()
            val room = fx.mock.addRoom(1001, "model", status = "public")

            fx.get("/add?name=model&active=true").expectOk("Room added: model")
            fx.awaitSession(1001, SessionState.Recording)
            fx.awaitEventCount<SegmentDownloaded>(1, 15.seconds) { it.roomId == 1001L }

            repeat(6) { index ->
                room.advanceSegment()
                fx.awaitEventCount<SegmentDownloaded>(index + 2, 20.seconds) { it.roomId == 1001L }
            }

            // several more polls see the same sliding window; dedupe must not re-fetch
            delay(500)
            assertEquals(
                7,
                fx.events.filterIsInstance<SegmentDownloaded>().count { it.roomId == 1001L },
                "init + 6 segments, each fetched once"
            )

            fx.get("/remove?id=1001").expectOk("Removed")
            val file = fx.awaitFile(1001)
            assertContentEquals(fx.mock.expectedBytes(1001, room.generation, throughIndex = 6), file.readBytes())
        }
    }

    @Test
    fun `init change cuts the file and restarts with the new generation`() = testApplication {
        XhrecIntegrationFixture(this).use { fx ->
            fx.start()
            fx.installRoutes()
            fx.ready()
            val room = fx.mock.addRoom(1001, "model", status = "public")

            fx.get("/add?name=model&active=true").expectOk("Room added: model")
            fx.awaitSession(1001, SessionState.Recording)
            fx.awaitEventCount<SegmentDownloaded>(1, 15.seconds) { it.roomId == 1001L }
            room.advanceSegment()
            room.advanceSegment()
            fx.awaitEventCount<SegmentDownloaded>(3, 20.seconds) { it.roomId == 1001L }

            // a new init URL forces the session to close the current file
            assertEquals(2, fx.mock.rotateInit(1001))
            val firstFile = fx.awaitFile(1001)
            assertContentEquals(fx.mock.expectedBytes(1001, 1, throughIndex = 2), firstFile.readBytes())

            // the restarted session re-downloads the new init plus the window of generation 2
            fx.awaitEventCount<SegmentDownloaded>(6, 20.seconds) { it.roomId == 1001L }
            room.advanceSegment()
            room.advanceSegment()
            fx.awaitEventCount<SegmentDownloaded>(8, 20.seconds) { it.roomId == 1001L }

            fx.get("/remove?id=1001").expectOk("Removed")
            val files = fx.awaitEventCount<FileReady>(2, 20.seconds) { it.roomId == 1001L }
            assertContentEquals(
                fx.mock.expectedBytes(1001, 2, throughIndex = 4),
                files[1].file.readBytes(),
                "second file must use generation-2 init and segment bytes"
            )
        }
    }

    @Test
    fun `404 is permanent and does not block later segments`() = testApplication {
        XhrecIntegrationFixture(this).use { fx ->
            fx.start()
            fx.installRoutes()
            fx.ready()
            val room = fx.mock.addRoom(1001, "model", status = "public")

            fx.get("/add?name=model&active=true").expectOk("Room added: model")
            fx.awaitSession(1001, SessionState.Recording)
            fx.awaitEventCount<SegmentDownloaded>(1, 15.seconds) { it.roomId == 1001L }

            val missing = MockPayloads.segmentPath(1001, room.generation, 1)
            fx.mock.failNext(missing, MockFault.NotFound)
            room.advanceSegment()
            room.advanceSegment()

            // init + segment 2 only; segment 1 is gone for good
            fx.awaitEventCount<SegmentDownloaded>(2, 20.seconds) { it.roomId == 1001L }
            delay(500)
            assertEquals(
                2,
                fx.events.filterIsInstance<SegmentDownloaded>().count { it.roomId == 1001L },
                "the 404 segment must not be delivered"
            )
            val attempts = fx.mock.requests().count { it.path == missing }
            assertTrue(attempts <= 2, "404 must not be retried on other hosts: $attempts attempts")

            fx.get("/remove?id=1001").expectOk("Removed")
            val file = fx.awaitFile(1001)
            assertContentEquals(
                fx.mock.expectedBytes(1001, room.generation, indices = listOf(2)),
                file.readBytes()
            )
        }
    }

    @Test
    fun `transient 500 is retried until the segment succeeds`() = testApplication {
        XhrecIntegrationFixture(this).use { fx ->
            fx.start()
            fx.installRoutes()
            fx.ready()
            val room = fx.mock.addRoom(1001, "model", status = "public")

            fx.get("/add?name=model&active=true").expectOk("Room added: model")
            fx.awaitSession(1001, SessionState.Recording)
            fx.awaitEventCount<SegmentDownloaded>(1, 15.seconds) { it.roomId == 1001L }

            val path = MockPayloads.segmentPath(1001, room.generation, 1)
            fx.mock.failNext(path, MockFault.ServerError)
            room.advanceSegment()

            fx.awaitEventCount<SegmentDownloaded>(2, 20.seconds) { it.roomId == 1001L }
            assertTrue(fx.mock.requests().count { it.path == path } >= 2, "500 must trigger a retry")

            fx.get("/remove?id=1001").expectOk("Removed")
            val file = fx.awaitFile(1001)
            assertContentEquals(fx.mock.expectedBytes(1001, room.generation, throughIndex = 1), file.readBytes())
        }
    }

    @Test
    fun `stalled attempt is abandoned and retried`() = testApplication {
        val tuning = XhrecIntegrationFixture.testTuning(
            downloaderRaceDelay = 10.seconds,
            downloaderStallTimeout = 400.milliseconds,
            downloaderDeadline = 10.seconds
        )
        withFixture(tuning) { fx ->
            fx.ready()
            val room = fx.mock.addRoom(1001, "model", status = "public")

            fx.get("/add?name=model&active=true").expectOk("Room added: model")
            fx.awaitSession(1001, SessionState.Recording)
            fx.awaitEventCount<SegmentDownloaded>(1, 15.seconds) { it.roomId == 1001L }

            val path = MockPayloads.segmentPath(1001, room.generation, 1)
            fx.mock.failNext(path, MockFault.Delay(2.seconds))
            room.advanceSegment()

            fx.awaitEventCount<SegmentDownloaded>(2, 25.seconds) { it.roomId == 1001L }
            assertTrue(fx.mock.requests().count { it.path == path } >= 2, "stalled attempt must be retried")

            fx.get("/remove?id=1001").expectOk("Removed")
            val file = fx.awaitFile(1001)
            assertContentEquals(fx.mock.expectedBytes(1001, room.generation, throughIndex = 1), file.readBytes())
        }
    }
}
