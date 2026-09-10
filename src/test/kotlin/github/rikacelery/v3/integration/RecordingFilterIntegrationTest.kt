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

    @Test
    fun `a private show is recorded for free when the account has free spy access`() = withFixture { fx ->
        fx.ready()
        val room = fx.mock.addRoom(1001, "model", status = "off")
        room.freeSpyAccess = true
        room.modelToken = "spy-token"

        // paid spy stays off: only the free privilege may start this recording
        fx.get("/add?name=model&active=false").expectOk("Room added: model")
        fx.get("/activate?id=1001").expectOk("Activated")
        fx.mock.startSegments(1001, 30.milliseconds)
        fx.awaitRoomSubscribed(1001)
        fx.mock.setRoomStatus(1001, "p2p")

        fx.awaitSession(1001, SessionState.Recording)
        fx.awaitEvent<SegmentDownloaded>(10.seconds) { it.roomId == 1001L }
        assertTrue(
            fx.mock.requests().none { it.method == "PUT" && it.path.contains("/spy") },
            "a free spy show must not be purchased: ${fx.mock.requests().map { "${it.method} ${it.path}" }}"
        )
    }

    @Test
    fun `a room without free spy access is probed once per private show`() = withFixture { fx ->
        fx.ready()
        fx.mock.addRoom(1001, "model", status = "off")   // freeSpyAccess defaults to false

        // paid spy stays off, so the free privilege is the only possible way in
        fx.get("/add?name=model&active=false").expectOk("Room added: model")
        fx.get("/activate?id=1001").expectOk("Activated")
        fx.mock.startSegments(1001, 30.milliseconds)
        fx.awaitRoomSubscribed(1001)
        val before = fx.mock.requests().size
        fx.mock.setRoomStatus(1001, "p2p")

        // the preconfig loop retries every 100ms in tests: an unthrottled probe would ask
        // the platform about the privilege ~20 times in this window
        delay(2000)
        val probes = fx.mock.requests().drop(before)
            .count { it.path.contains("/api/front/v2/models/1001/cam") }

        assertTrue(probes <= 3, "the free spy privilege must be probed once per private show, saw $probes probes")
        assertTrue(
            fx.sessions().none { it.roomId == 1001L && it.state == SessionState.Recording },
            "a private show without free spy access must not be recorded"
        )
    }

    @Test
    fun `the room filters are persisted to list conf`() = withFixture { fx ->
        fx.ready()
        fx.mock.addRoom(1001, "model", status = "off")
        fx.get("/add?name=model&active=false").expectOk("Room added: model")

        fx.get("/filter?id=1001&kind=public&v=false").expectOk("Filter public set to false")
        fx.get("/filter?id=1001&kind=freespy&v=false").expectOk("Filter freespy set to false")

        val line = fx.awaitListConf(15.seconds) { it.contains("nopublic") }
        assertTrue(line.contains("nofreespy"), "both filters must be written: $line")
        assertTrue(line.contains("q:highest"), "the rest of the line stays untouched: $line")
    }

    @Test
    fun `a private show is not recorded when free spy recording is disabled`() = withFixture { fx ->
        fx.ready()
        val room = fx.mock.addRoom(1001, "model", status = "off")
        room.freeSpyAccess = true
        room.modelToken = "spy-token"

        fx.get("/add?name=model&active=false").expectOk("Room added: model")
        fx.get("/filter?id=1001&kind=freespy&v=false").expectOk("Filter freespy set to false")
        fx.get("/activate?id=1001").expectOk("Activated")
        fx.mock.startSegments(1001, 30.milliseconds)
        fx.awaitRoomSubscribed(1001)
        fx.mock.setRoomStatus(1001, "p2p")

        delay(1500)
        assertTrue(
            fx.sessions().none { it.roomId == 1001L && it.state == SessionState.Recording },
            "free spy recording is off, so the private show must not be recorded"
        )
        assertTrue(fx.events.filterIsInstance<SegmentDownloaded>().isEmpty())
    }

    @Test
    fun `a later private show probes for the free spy privilege again`() = withFixture { fx ->
        fx.ready()
        fx.mock.addRoom(1001, "model", status = "off")
        fx.get("/add?name=model&active=false").expectOk("Room added: model")
        fx.get("/activate?id=1001").expectOk("Activated")
        fx.mock.startSegments(1001, 30.milliseconds)
        fx.awaitRoomSubscribed(1001)

        fx.mock.setRoomStatus(1001, "p2p")
        delay(750)
        val firstShow = fx.mock.requests().count { it.path.contains("/api/front/v2/models/1001/cam") }
        assertTrue(firstShow > 0, "the privilege must be probed when the private show starts")

        fx.mock.setRoomStatus(1001, "off")
        delay(250)
        fx.mock.setRoomStatus(1001, "p2p")
        delay(750)

        val secondShow = fx.mock.requests().count { it.path.contains("/api/front/v2/models/1001/cam") }
        assertTrue(
            secondShow > firstShow,
            "the next private show must probe again instead of staying exhausted forever"
        )
    }
}
