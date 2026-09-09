package github.rikacelery.v3.integration

import github.rikacelery.v3.components.SessionState
import github.rikacelery.v3.data.RuntimeTuning
import github.rikacelery.v3.events.EndReason
import github.rikacelery.v3.events.FileReady
import github.rikacelery.v3.events.SegmentDownloaded
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
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
        assertEquals(HttpStatusCode.BadRequest, fx.get("/autopay?id=1001").status)
        assertEquals(HttpStatusCode.BadRequest, fx.get("/autopay?id=1001&v=true&kind=bogus").status)
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

        fx.get("/break?id=1001").expectOk("Break signaled")
        val broken = fx.awaitEvent<FileReady>(15.seconds) { it.roomId == 1001L }
        assertEquals(EndReason.NewInit, broken.reason)

        // the scheduler preconfigures again automatically after a break and keeps recording
        fx.awaitEventCount<SegmentDownloaded>(3, 15.seconds) { it.roomId == 1001L }

        // /restart stops the running session (closing its file) and arms the room again
        fx.get("/restart?id=1001").expectOk("Restarted")
        val afterRestart = fx.awaitEventCount<FileReady>(2, 15.seconds) { it.roomId == 1001L }
        assertEquals(EndReason.UserStop, afterRestart[1].reason)

        // an armed room resumes when the stream status changes again
        fx.mock.setRoomStatus(1001, "off")
        fx.mock.setRoomStatus(1001, "public")
        fx.awaitEventCount<SegmentDownloaded>(5, 15.seconds) { it.roomId == 1001L }

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

        // switch the room to a paid private show; the old recording is cut and a spy show is purchased
        fx.mock.room(1001).modelToken = ""
        fx.mock.setRoomStatus(1001, "p2p")

        fx.awaitEvent<FileReady>(15.seconds) { it.roomId == 1001L }
        fx.awaitSession(1001, SessionState.Recording)
        assertTrue(
            fx.mock.requests().any { it.method == "PUT" && it.path.contains("/spy") },
            "spy show must be purchased: ${fx.mock.requests().map { "${it.method} ${it.path}" }}"
        )
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

private fun withFixture(
    tuning: RuntimeTuning = XhrecIntegrationFixture.testTuning(),
    block: suspend (XhrecIntegrationFixture) -> Unit
) = testApplication {
    XhrecIntegrationFixture(this, tuning = tuning).use { fx ->
        fx.start()
        fx.installRoutes()
        block(fx)
    }
}

/** Adds an inactive room, activates it, publishes public status and waits for recording. */
private suspend fun XhrecIntegrationFixture.startRecording(
    roomId: Long = 1001L,
    name: String = "model",
    status: String = "public",
    segmentPeriod: Duration = 30.milliseconds
) {
    mock.addRoom(roomId, name, status = status)
    get("/add?name=$name&active=false").expectOk("Room added: $name")
    awaitRoom(name) { !it.bool("listening") }
    get("/activate?id=$roomId").expectOk("Activated")
    mock.startSegments(roomId, segmentPeriod)
    awaitRoomSubscribed(roomId)
    mock.setRoomStatus(roomId, status)
    awaitSession(roomId, SessionState.Recording)
    awaitEvent<SegmentDownloaded>(10.seconds) { it.roomId == roomId }
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
