package github.rikacelery.v3.integration

import github.rikacelery.v3.components.SessionState
import github.rikacelery.v3.events.CutPointDone
import github.rikacelery.v3.events.EndReason
import github.rikacelery.v3.events.FileReady
import github.rikacelery.v3.events.RecordingStarted
import github.rikacelery.v3.events.SegmentDownloaded
import github.rikacelery.v3.events.SessionExit
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Room control routes driven through the production HTTP application against the mock
 * platform: the real Room/Scheduler/Session/Downloader/Writer actors do the work.
 */
class RoomRoutesIntegrationTest {

    @Test
    fun `add inactive activate record remove closes the file`() = withFixture { fx ->
        fx.ready()
        fx.mock.addRoom(1001, "model", status = "off")

        fx.get("/add?name=model&active=false").expectOk("Room added: model")
        fx.awaitRoom("model") { !it.bool("listening") }
        assertEquals(listOf(1001L), fx.rooms().map { it.id })
        assertTrue(fx.sessions().isEmpty(), "inactive room must not have a session")

        fx.get("/activate?id=1001").expectOk("Activated")
        fx.mock.startSegments(1001, 30.milliseconds)
        fx.awaitRoomSubscribed(1001)
        fx.mock.setRoomStatus(1001, "public")

        fx.awaitSession(1001, SessionState.Recording)
        fx.awaitEvent<SegmentDownloaded>(10.seconds) { it.roomId == 1001L }

        fx.get("/remove?id=1001").expectOk("Removed")
        val file = fx.awaitFile(1001)
        assertTrue(file.exists(), "recorded file must exist: ${file.absolutePath}")
        assertTrue(file.length() > 0, "recorded file must not be empty")
        fx.awaitRoomAbsent(1001)
    }

    @Test
    fun `active add arms and records without a separate activate`() = withFixture { fx ->
        fx.ready()
        fx.mock.addRoom(1002, "active", status = "public")
        fx.mock.startSegments(1002, 30.milliseconds)

        fx.get("/add?name=active&active=true").expectOk("Room added: active")
        fx.awaitSession(1002, SessionState.Recording)
        fx.awaitEvent<SegmentDownloaded>(10.seconds) { it.roomId == 1002L }
        assertTrue(fx.awaitRoom("active") { it.bool("listening") && it.path("session.active") == "true" }.isNotEmpty())
    }

    @Test
    fun `invalid and duplicate input is rejected`() = withFixture { fx ->
        fx.ready()
        fx.mock.addRoom(1001, "model", status = "off")

        val missingName = fx.get("/add")
        assertEquals(HttpStatusCode.BadRequest, missingName.status)
        assertEquals("Missing name", missingName.bodyAsText())

        val unknown = fx.get("/add?name=ghost")
        assertEquals(HttpStatusCode.InternalServerError, unknown.status)
        assertTrue(unknown.bodyAsText().contains("failed to add room"), unknown.bodyAsText())

        fx.get("/add?name=model").expectOk("Room added: model")
        val duplicate = fx.get("/add?name=model")
        assertEquals(HttpStatusCode.InternalServerError, duplicate.status)
        assertTrue(duplicate.bodyAsText().contains("Exist model"), duplicate.bodyAsText())

        assertEquals(HttpStatusCode.BadRequest, fx.get("/remove").status)
        assertEquals(HttpStatusCode.BadRequest, fx.get("/activate").status)
        assertEquals(HttpStatusCode.BadRequest, fx.get("/deactivate").status)
        assertEquals(HttpStatusCode.BadRequest, fx.get("/quality").status)
        assertEquals(HttpStatusCode.BadRequest, fx.get("/limit").status)
        assertEquals(HttpStatusCode.BadRequest, fx.get("/sizelimit").status)
        assertEquals(HttpStatusCode.BadRequest, fx.get("/filter?id=1001&kind=public").status)
        assertEquals(HttpStatusCode.BadRequest, fx.get("/filter?id=1001&kind=bogus&v=true").status)
    }

    @Test
    fun `remove while preconfiguring cancels the arm`() = withFixture { fx ->
        fx.ready()
        fx.mock.addRoom(1001, "model", status = "public")
        // keep the room in Preconfiguring by stalling its master playlist
        fx.mock.failNext(fx.mock.masterPath(1001), MockFault.Delay(3.seconds))

        fx.get("/add?name=model").expectOk("Room added: model")
        fx.get("/activate?id=1001").expectOk("Activated")
        fx.await(10.seconds, "master playlist request") {
            fx.mock.requests().firstOrNull { it.path == fx.mock.masterPath(1001) }
        }

        fx.get("/remove?id=1001").expectOk("Removed")
        fx.awaitRoomAbsent(1001)
        // Barrier: the scheduler has processed the removal and is idle, so nothing is left to arm.
        fx.awaitSchedulerRoomGone(1001)
        assertTrue(fx.sessions().isEmpty(), "a removed room must not start a session")
        assertTrue(fx.events.filterIsInstance<SegmentDownloaded>().isEmpty(), "a removed room must not download")
    }

    @Test
    fun `deactivate stops the recording and closes the file`() = withFixture { fx ->
        fx.ready()
        fx.startRecording()

        fx.get("/deactivate?id=1001").expectOk("Deactivated")
        val ready = fx.awaitEvent<FileReady>(15.seconds) { it.roomId == 1001L }
        assertEquals(EndReason.UserStop, ready.reason)
        assertTrue(ready.file.length() > 0)
        fx.awaitSession(1001, SessionState.Idle)
        // the room stays in list.conf (disarmed) and its session entry is retained as Idle
        fx.awaitRoom("model") { it.path("session.status") == "Idle" && it.path("session.active") == "false" }
    }

    @Test
    fun `break and restart produce separate files`() = withFixture { fx ->
        fx.ready()
        fx.startRecording()
        val initialDownload = fx.awaitEvent<SegmentDownloaded> { it.roomId == 1001L }

        fx.get("/break?id=1001").expectOk("Break signaled")
        val broken = fx.awaitEvent<FileReady>(15.seconds) { it.roomId == 1001L }
        assertEquals(EndReason.NewInit, broken.reason)

        // the scheduler preconfigures again automatically after a break and keeps recording
        fx.awaitEventCount<RecordingStarted>(2, 15.seconds) { it.roomId == 1001L }
        // RecordingStarted precedes playlist polling. Wait for bytes in this generation
        // before closing it, otherwise the writer correctly discards an empty file.
        val afterBreakDownload = fx.awaitEvent<SegmentDownloaded>(20.seconds) {
            it.roomId == 1001L && it.generation != initialDownload.generation
        }

        // /restart stops the running session (closing its file) and arms the room again
        fx.get("/restart?id=1001").expectOk("Restarted")
        val afterRestart = fx.awaitEventCount<FileReady>(2, 15.seconds) { it.roomId == 1001L }
        assertEquals(EndReason.UserStop, afterRestart[1].reason)

        // the room is still public, so re-arming it records again without a status change
        fx.awaitEventCount<RecordingStarted>(3, 20.seconds) { it.roomId == 1001L }
        fx.awaitEvent<SegmentDownloaded>(20.seconds) {
            it.roomId == 1001L && it.generation != initialDownload.generation &&
                it.generation != afterBreakDownload.generation
        }

        fx.get("/remove?id=1001").expectOk("Removed")
        val all = fx.awaitEventCount<FileReady>(3, 15.seconds) { it.roomId == 1001L }
        assertEquals(
            listOf(EndReason.NewInit, EndReason.UserStop, EndReason.UserStop),
            all.map { it.reason },
            "each cut must publish its own FileReady"
        )
        assertTrue(all.all { it.file.exists() && it.file.length() > 0 }, "every file must have bytes")
    }

    @Test
    fun `quality change restarts the recording at the requested variant`() = withFixture { fx ->
        fx.ready()
        fx.startRecording()

        // "highest" resolves to the top variant of the mock ladder
        fx.awaitSession(1001, SessionState.Recording, quality = "720p")
        // bytes before the cut: the writer discards an empty recording, so a restart before the
        // first segment would publish no FileReady at all
        fx.awaitEvent<SegmentDownloaded>(15.seconds) { it.roomId == 1001L }

        fx.get("/quality?id=1001&q=360p").expectOk("Quality set to 360p")
        fx.awaitRoom("model") { it.path("room.quality") == "360p" }

        fx.awaitEvent<FileReady>(15.seconds) { it.roomId == 1001L }
        fx.awaitSession(1001, SessionState.Recording, quality = "360p")
    }

    @Test
    fun `time limit stops the recording`() = withFixture { fx ->
        fx.ready()
        fx.mock.addRoom(1001, "model", status = "public")
        fx.mock.startSegments(1001, 30.milliseconds)

        fx.get("/add?name=model&limit=1").expectOk("Room added: model")
        fx.get("/activate?id=1001").expectOk("Activated")
        fx.awaitSession(1001, SessionState.Recording)

        val ready = fx.awaitEvent<FileReady>(20.seconds) { it.roomId == 1001L }
        assertEquals(EndReason.TimeLimit, ready.reason)
    }

    @Test
    fun `a time limit changed while recording applies to the restarted session`() = withFixture { fx ->
        fx.ready()
        fx.mock.addRoom(1001, "model", status = "public")
        fx.mock.startSegments(1001, 30.milliseconds)

        fx.get("/add?name=model&limit=1").expectOk("Room added: model")
        fx.get("/activate?id=1001").expectOk("Activated")
        fx.awaitSession(1001, SessionState.Recording)
        fx.awaitEvent<SegmentDownloaded>(15.seconds) { it.roomId == 1001L }

        // lift the limit while the first session runs: the limit-driven restart must pick it up
        fx.get("/limit?id=1001&v=0").expectOk("Time limit set to 0s")

        fx.awaitEventCount<FileReady>(1, 20.seconds) { it.roomId == 1001L && it.reason == EndReason.TimeLimit }

        // Read the limit the restarted session actually applied (-1 = unlimited) instead of sleeping
        // to see whether it cuts again; that is the property under test.
        val applied = fx.await(10.seconds, "the restarted session to apply the new limit") {
            fx.sessionEntry(1001)?.get("timeLimitMs")?.jsonPrimitive?.longOrNull?.takeIf { it == -1L }
        }
        assertEquals(-1L, applied, "the restarted session must use the new limit instead of the stale one")
        assertEquals(
            1,
            fx.events.filterIsInstance<FileReady>()
                .count { it.roomId == 1001L && it.reason == EndReason.TimeLimit },
            "the restarted session must not cut again on the stale limit"
        )
    }

    @Test
    fun `size limit stops the recording`() = withFixture { fx ->
        fx.ready()
        fx.mock.addRoom(1001, "model", status = "public")
        fx.mock.startSegments(1001, 30.milliseconds)

        // 1Ki = 1024 bytes: init (128) + two 512-byte segments crosses the limit
        fx.get("/add?name=model&size=1Ki").expectOk("Room added: model")
        fx.get("/activate?id=1001").expectOk("Activated")
        fx.awaitSession(1001, SessionState.Recording)

        val ready = fx.awaitEvent<FileReady>(20.seconds) { it.roomId == 1001L }
        assertEquals(EndReason.SizeLimit, ready.reason)
        assertTrue(ready.file.length() >= 1024, "file must have crossed the size limit: ${ready.file.length()}")
    }

    @Test
    fun `a session stopped before its first segment still exits`() = withFixture(
        // a playlist poll interval longer than the test: the cut lands before the session has
        // polled once, so the downloader has no state for the room yet
        tuning = XhrecIntegrationFixture.testTuning(playlistPollInterval = 30.seconds)
    ) { fx ->
        fx.ready()
        fx.mock.addRoom(1001, "model", status = "public")
        fx.mock.startSegments(1001, 30.milliseconds)

        fx.get("/add?name=model").expectOk("Room added: model")
        fx.get("/activate?id=1001").expectOk("Activated")
        fx.awaitSession(1001, SessionState.Recording)

        fx.get("/break?id=1001").expectOk("Break signaled")

        // nothing was downloaded yet, so there is no file to publish — but the cut still has to
        // complete: otherwise the session hangs in Closing and the room never records again
        val cut = fx.awaitEvent<CutPointDone>(15.seconds) { it.roomId == 1001L }
        assertEquals(EndReason.NewInit, cut.reason)
        fx.awaitEvent<SessionExit>(15.seconds) { it.roomId == 1001L }
        fx.awaitEventCount<RecordingStarted>(2, 15.seconds) { it.roomId == 1001L }
    }

    @Test
    fun `group show and private autopay both record`() = withFixture { fx ->
        fx.ready()
        fx.mock.addRoom(1001, "model", status = "groupShow")
        fx.mock.startSegments(1001, 30.milliseconds)

        fx.get("/add?name=model&autopayTicket=true&autoPaySpy=true").expectOk("Room added: model")
        fx.get("/activate?id=1001").expectOk("Activated")

        fx.awaitSession(1001, SessionState.Recording)
        assertTrue(
            fx.mock.requests().any { it.method == "POST" && it.path.contains("/groupShows/") },
            "group show must be purchased: ${fx.mock.requests().map { "${it.method} ${it.path}" }}"
        )
        // bytes before the cut: switching the status before the first segment is downloaded
        // would close an empty recording, which the writer discards without a FileReady
        fx.awaitEvent<SegmentDownloaded>(15.seconds) { it.roomId == 1001L }

        // switch the room to a paid private show; the old recording is cut and a spy show is purchased
        fx.mock.room(1001).modelToken = ""
        fx.mock.setRoomStatus(1001, "private")

        fx.awaitEvent<FileReady>(15.seconds) { it.roomId == 1001L }
        // the stopped session still reports Recording while it closes, so waiting for the state
        // alone can return before anything was purchased: wait for the purchase and the next session
        fx.await(15.seconds, "spy show purchase") {
            fx.mock.requests().any { it.method == "PUT" && it.path.contains("/spy") }.takeIf { it }
        }
        fx.awaitEventCount<RecordingStarted>(2, 15.seconds) { it.roomId == 1001L }
    }

    @Test
    fun `list and dashboard expose room state`() = withFixture { fx ->
        fx.ready()
        fx.startRecording()

        val list = Json.parseToJsonElement(fx.get("/list").bodyAsText()).jsonArray
        val entry = list.single { it.jsonArray[3].jsonPrimitive.content == "model" }.jsonArray
        assertEquals("public", entry[0].jsonPrimitive.content)
        assertEquals("listening", entry[1].jsonPrimitive.content)
        assertEquals("recording", entry[2].jsonPrimitive.content)
        assertEquals("1001", entry[4].jsonPrimitive.content)
        assertEquals("highest", entry[5].jsonPrimitive.content)

        val dashboard = fx.dashboard()
        assertEquals(1, dashboard["rooms"]!!.jsonArray.size)
        val listv2 = dashboard["listv2"]!!.jsonArray.single().jsonObject
        assertEquals("model", listv2.path("room.name"))
        assertTrue(listv2.bool("listening"))
        assertTrue(dashboard["metrics"]!!.jsonPrimitive.content.isNotEmpty())

        val status = Json.parseToJsonElement(fx.get("/status").bodyAsText()).jsonObject
        assertTrue(status.isNotEmpty(), "status must report the active room")
    }

    @Test
    fun `list conf persists armed and disarmed rooms`() = withFixture { fx ->
        fx.ready()
        fx.mock.addRoom(1001, "model", status = "off")

        fx.get("/add?name=model&active=false").expectOk("Room added: model")
        val disarmed = fx.awaitListConf(15.seconds) { it.contains("model") }
        assertTrue(disarmed.trimStart().startsWith("#"), "inactive room must be commented out: $disarmed")

        fx.get("/activate?id=1001").expectOk("Activated")
        val armed = fx.awaitListConf(15.seconds) { it.contains("model") && !it.trimStart().startsWith("#") }
        assertTrue(armed.contains("q:highest"), armed)

        fx.get("/deactivate?id=1001").expectOk("Deactivated")
        fx.awaitListConf(15.seconds) { it.contains("model") && it.trimStart().startsWith("#") }
    }

    @Test
    fun `the dashboard exposes the room filters`() = withFixture { fx ->
        fx.ready()
        fx.mock.addRoom(1001, "model", status = "off")
        fx.get("/add?name=model&active=false").expectOk("Room added: model")
        fx.get("/filter?id=1001&kind=public&v=false").expectOk("Filter public set to false")

        val displayed = fx.awaitRoom("model") { it.path("room.recordPublic") == "false" }
        val room = displayed["room"]!!.jsonObject
        assertFalse(room.boolField("recordPublic"), "the dashboard must show the public filter")
        assertTrue(room.boolField("recordFreeSpy"), "untouched filters keep their defaults")
    }

    @Test
    fun `the filter route rejects unknown kinds and missing values`() = withFixture { fx ->
        fx.ready()
        fx.mock.addRoom(1001, "model", status = "off")
        fx.get("/add?name=model&active=false").expectOk("Room added: model")

        assertEquals(HttpStatusCode.BadRequest, fx.get("/filter?id=1001&kind=bogus&v=true").status)
        assertEquals(HttpStatusCode.BadRequest, fx.get("/filter?id=1001&kind=public").status)
        assertEquals(HttpStatusCode.BadRequest, fx.get("/filter?id=1001&kind=public&v=maybe").status)
        assertEquals(HttpStatusCode.BadRequest, fx.get("/filter?kind=public&v=true").status)
    }

    @Test
    fun `list conf accepts a commented room with a space after the marker`() = withFixture { fx ->
        fx.mock.addRoom(1001, "model", status = "off")
        File(fx.listConfPath).writeText("# https://mock/model q:720p nopublic\n")

        fx.bootstrap()

        val room = fx.rooms().single { it.id == 1001L }
        assertEquals("720p", room.quality)
        assertFalse(room.recordPublic, "filters on a commented line are parsed too")
        assertTrue(fx.sessions().isEmpty(), "a commented room must not be armed")
    }

    @Test
    fun `bootstrap reloads the room filters from list conf`() = withFixture { fx ->
        fx.mock.addRoom(1001, "model", status = "off")
        File(fx.listConfPath).writeText("#https://mock/model q:highest nopublic nofreespy\n")

        fx.bootstrap()

        val room = fx.rooms().single { it.id == 1001L }
        assertFalse(room.recordPublic, "nopublic must turn public recording off")
        assertFalse(room.recordFreeSpy, "nofreespy must turn free spy recording off")
    }

    @Test
    fun `bootstrap reloads list conf and records armed rooms`() = withFixture { fx ->
        fx.mock.addRoom(1001, "model", status = "public")
        fx.mock.startSegments(1001, 30.milliseconds)
        File(fx.listConfPath).writeText("https://mock/model q:720p limit:60 autopay\n")

        fx.bootstrap()

        val room = fx.rooms().single { it.id == 1001L }
        assertEquals("720p", room.quality)
        assertEquals(60.seconds, room.timeLimit)
        assertTrue(room.autoPayTicket && room.autoPaySpy, "bare 'autopay' enables both kinds")
        fx.awaitSession(1001, SessionState.Recording, quality = "720p")
    }
}

private fun JsonObject.bool(field: String): Boolean =
    this[field]?.jsonPrimitive?.content?.toBoolean() ?: false

internal fun JsonObject.path(path: String): String? {
    var element: kotlinx.serialization.json.JsonElement = this
    for (part in path.split('.')) {
        element = element.jsonObject[part] ?: return null
    }
    return element.jsonPrimitive.content
}

internal suspend fun HttpResponse.expectOk() {
    val text = bodyAsText()
    assertEquals(HttpStatusCode.OK, status, "unexpected status, body=$text")
}

internal suspend fun HttpResponse.expectOk(expectedBody: String) {
    val text = bodyAsText()
    assertEquals(HttpStatusCode.OK, status, "unexpected status, body=$text")
    assertEquals(expectedBody, text)
}
