package github.rikacelery.v3.integration

import github.rikacelery.v3.components.SessionState
import github.rikacelery.v3.events.EndReason
import github.rikacelery.v3.events.FileReady
import github.rikacelery.v3.events.SegmentDownloaded
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Status-driven recording decisions plus byte-exact output: the file the Writer closes must
 * equal the mock's init+segment bytes, in order, no matter how downloads complete.
 */
class StatusFlowIntegrationTest {

    @ParameterizedTest(name = "status={0} ticket={1} spy={2} records={3}")
    @CsvSource(
        "off,false,false,false",
        "public,false,false,true",
        "groupShow,false,false,false",
        "groupShow,true,false,true",
        "p2p,false,false,false",
        "p2p,false,true,false",
        "private,false,true,true",
        "virtualPrivate,false,true,true",
        "weirdStatus,false,false,false",
        "weirdStatus,false,true,true"
    )
    fun `room status gates recording`(
        status: String,
        autoPayTicket: Boolean,
        autoPaySpy: Boolean,
        expectRecording: Boolean
    ) = testApplication {
        XhrecIntegrationFixture(this).use { fx ->
            fx.start()
            fx.installRoutes()
            fx.ready()
            fx.mock.addRoom(1001, "model", status = "off")

            fx.get("/add?name=model&autopayTicket=$autoPayTicket&autoPaySpy=$autoPaySpy")
                .expectOk("Room added: model")
            fx.get("/activate?id=1001").expectOk("Activated")
            fx.mock.startSegments(1001, 40.milliseconds)
            fx.awaitRoomSubscribed(1001)
            fx.mock.setStreamStatus(1001, "distributing")
            fx.mock.setRoomStatus(1001, status)

            if (expectRecording) {
                fx.awaitSession(1001, SessionState.Recording)
                fx.awaitEvent<SegmentDownloaded>(15.seconds) { it.roomId == 1001L }
            } else {
                // bounded negative: the room must stay quiet while the status is not recordable
                delay(1500)
                assertTrue(
                    fx.sessions().none { it.roomId == 1001L && it.state == SessionState.Recording },
                    "room must not record while status=$status"
                )
                assertTrue(
                    fx.events.filterIsInstance<SegmentDownloaded>().isEmpty(),
                    "no segment may be fetched while status=$status"
                )
            }
        }
    }

    @Test
    fun `recorded file equals mock init and segment bytes`() = testApplication {
        XhrecIntegrationFixture(this).use { fx ->
            fx.start()
            fx.installRoutes()
            fx.ready()
            val room = fx.mock.addRoom(1001, "model", status = "off")

            fx.get("/add?name=model").expectOk("Room added: model")
            fx.get("/activate?id=1001").expectOk("Activated")
            fx.awaitRoomSubscribed(1001)
            fx.mock.setRoomStatus(1001, "public")
            fx.awaitSession(1001, SessionState.Recording)

            // one segment at a time keeps the expected file deterministic
            fx.awaitEventCount<SegmentDownloaded>(1, 15.seconds) { it.roomId == 1001L }
            repeat(4) { index ->
                room.advanceSegment()
                fx.awaitEventCount<SegmentDownloaded>(index + 2, 15.seconds) { it.roomId == 1001L }
            }

            fx.mock.setRoomStatus(1001, "off")
            val ready = fx.awaitFile(1001)
            assertContentEquals(
                fx.mock.expectedBytes(1001, room.generation, throughIndex = 4),
                ready.readBytes(),
                "file must contain init + segments 1..4 in order"
            )
        }
    }

    @Test
    fun `out-of-order downloads are still written in order`() = testApplication {
        XhrecIntegrationFixture(this).use { fx ->
            fx.start()
            fx.installRoutes()
            fx.ready()
            val room = fx.mock.addRoom(1001, "model", status = "public")

            fx.get("/add?name=model&active=true").expectOk("Room added: model")
            fx.awaitSession(1001, SessionState.Recording)
            fx.awaitEventCount<SegmentDownloaded>(1, 15.seconds) { it.roomId == 1001L }

            // segment 1 completes long after segment 2
            fx.mock.delaySegment(1001, 1, 400.milliseconds)
            room.advanceSegment()
            room.advanceSegment()
            fx.awaitEventCount<SegmentDownloaded>(3, 20.seconds) { it.roomId == 1001L }

            val completion = fx.mock.completionOrder(1001).distinct()
            assertTrue(
                completion.indexOf(2) < completion.indexOf(1),
                "mock must finish segment 2 first: $completion"
            )

            fx.get("/remove?id=1001").expectOk("Removed")
            val ready = fx.awaitFile(1001)
            assertContentEquals(
                fx.mock.expectedBytes(1001, room.generation, throughIndex = 2),
                ready.readBytes(),
                "writer must reorder completions into segment order"
            )
        }
    }

    @Test
    fun `finished stream status stops the recording`() = testApplication {
        XhrecIntegrationFixture(this).use { fx ->
            fx.start()
            fx.installRoutes()
            fx.ready()
            fx.startRecording()

            fx.mock.setStreamStatus(1001, "finished")

            val ready = fx.awaitEvent<FileReady>(15.seconds) { it.roomId == 1001L }
            assertEquals(EndReason.StreamEnd, ready.reason)
            assertTrue(ready.file.length() > 0)
        }
    }

    @Test
    fun `automatic status rotation produces one file per episode`() = testApplication {
        XhrecIntegrationFixture(this).use { fx ->
            fx.start()
            fx.installRoutes()
            fx.ready()
            fx.mock.addRoom(1001, "model", status = "off")

            fx.get("/add?name=model").expectOk("Room added: model")
            fx.get("/activate?id=1001").expectOk("Activated")
            fx.mock.startSegments(1001, 40.milliseconds)
            fx.awaitRoomSubscribed(1001)

            val rotation = fx.mock.rotateStatuses(1001, listOf("public", "off"), 900.milliseconds)
            try {
                fx.awaitSession(1001, SessionState.Recording, timeout = 20.seconds)
                val files = fx.awaitEventCount<FileReady>(2, 30.seconds) { it.roomId == 1001L }
                assertEquals(2, files.size, "each off phase must close one file")
                assertTrue(files.all { it.reason == EndReason.StreamEnd }, files.map { it.reason }.toString())
                assertTrue(files.all { it.file.length() > 0 })
            } finally {
                rotation.cancelAndJoin()
            }
        }
    }

    @Test
    fun `two rooms record isolated bytes`() = testApplication {
        XhrecIntegrationFixture(this).use { fx ->
            fx.start()
            fx.installRoutes()
            fx.ready()
            val first = fx.mock.addRoom(1001, "modelA", status = "public")
            val second = fx.mock.addRoom(1002, "modelB", status = "public")

            fx.get("/add?name=modelA&active=true").expectOk("Room added: modelA")
            fx.get("/add?name=modelB&active=true").expectOk("Room added: modelB")
            fx.awaitSession(1001, SessionState.Recording)
            fx.awaitSession(1002, SessionState.Recording)
            fx.awaitEventCount<SegmentDownloaded>(1, 15.seconds) { it.roomId == 1001L }
            fx.awaitEventCount<SegmentDownloaded>(1, 15.seconds) { it.roomId == 1002L }

            repeat(2) { index ->
                first.advanceSegment()
                second.advanceSegment()
                fx.awaitEventCount<SegmentDownloaded>(index + 2, 15.seconds) { it.roomId == 1001L }
                fx.awaitEventCount<SegmentDownloaded>(index + 2, 15.seconds) { it.roomId == 1002L }
            }

            fx.get("/remove?id=1001").expectOk("Removed")
            fx.get("/remove?id=1002").expectOk("Removed")
            val fileA = fx.awaitFile(1001).readBytes()
            val fileB = fx.awaitFile(1002).readBytes()

            assertContentEquals(fx.mock.expectedBytes(1001, first.generation, throughIndex = 2), fileA)
            assertContentEquals(fx.mock.expectedBytes(1002, second.generation, throughIndex = 2), fileB)
            assertTrue(!fileA.contentEquals(fileB), "rooms must not share segment bytes")
        }
    }
}
