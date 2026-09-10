package github.rikacelery.v3.integration

import github.rikacelery.v3.components.SessionState
import github.rikacelery.v3.events.EndReason
import github.rikacelery.v3.events.FileReady
import github.rikacelery.v3.events.SegmentDownloaded
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Per-room recording filters (issue #130): the switches decide which kinds of show a room is
 * armed for, and turning one off must take effect on a room that is already armed.
 */
class RecordingFilterIntegrationTest {

    @Test
    fun `public show is not recorded when public recording is disabled`() = withFixture { fx ->
        fx.ready()
        fx.mock.addRoom(1001, "model", status = "off")
        fx.get("/add?name=model&active=false").expectOk("Room added: model")

        fx.get("/filter?id=1001&kind=public&v=false").expectOk("Filter public set to false")

        fx.get("/activate?id=1001").expectOk("Activated")
        fx.mock.startSegments(1001, 30.milliseconds)
        fx.awaitRoomSubscribed(1001)
        fx.mock.setRoomStatus(1001, "public")

        // bounded negative: the room must stay quiet while public recording is off
        delay(1500)
        assertTrue(
            fx.sessions().none { it.roomId == 1001L && it.state == SessionState.Recording },
            "a public show must not be recorded while public recording is off: ${fx.sessions()}"
        )
        assertTrue(
            fx.events.filterIsInstance<SegmentDownloaded>().isEmpty(),
            "no segment may be fetched while public recording is off"
        )
    }

    @Test
    fun `enabling public recording starts an already armed room`() = withFixture { fx ->
        fx.ready()
        fx.mock.addRoom(1001, "model", status = "off")
        fx.get("/add?name=model&active=false").expectOk("Room added: model")
        fx.get("/filter?id=1001&kind=public&v=false").expectOk("Filter public set to false")

        fx.get("/activate?id=1001").expectOk("Activated")
        fx.mock.startSegments(1001, 30.milliseconds)
        fx.awaitRoomSubscribed(1001)
        fx.mock.setRoomStatus(1001, "public")
        delay(750)
        assertTrue(
            fx.sessions().none { it.roomId == 1001L && it.state == SessionState.Recording },
            "precondition: the armed room stays quiet while public recording is off"
        )

        fx.get("/filter?id=1001&kind=public&v=true").expectOk("Filter public set to true")

        // the room is already public: turning the filter back on must start it without a status change
        fx.awaitSession(1001, SessionState.Recording)
        fx.awaitEvent<SegmentDownloaded>(10.seconds) { it.roomId == 1001L }
    }

    @Test
    fun `disabling public recording stops a running recording`() = withFixture { fx ->
        fx.ready()
        fx.startRecording()

        fx.get("/filter?id=1001&kind=public&v=false").expectOk("Filter public set to false")

        val file = fx.awaitEvent<FileReady>(15.seconds) { it.roomId == 1001L }
        assertEquals(EndReason.StatusChanged, file.reason)

        // bounded negative: the new filter must keep the room quiet, not restart it
        delay(750)
        assertTrue(
            fx.sessions().none { it.roomId == 1001L && it.state == SessionState.Recording },
            "a public show must stay stopped after the filter was turned off: ${fx.sessions()}"
        )
        assertTrue(
            fx.mock.requests().none { it.path.contains("/hls/1001/master") && it.atMs > file.endTime },
            "no new preconfig may run after public recording was turned off"
        )
    }
}
