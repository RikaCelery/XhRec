package github.rikacelery.v3.integration

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The introspection surface an operator reaches for when a room looks stuck but the logs are
 * quiet: which components are alive, what each state machine is doing, and how it got there.
 */
class DiagnosticsEndpointTest {

    @Test
    fun `diagnose lists the live components and their sections`() = withFixture { fx ->
        val index = fx.get("/diagnose").bodyAsJson().jsonObject
        val components = index["components"]!!.jsonArray.map { it.jsonObject }
        val byName = components.associateBy { it["name"]!!.jsonPrimitive.content }

        for (expected in listOf(
            "SchedulerComponent", "SessionComponent", "DownloaderComponent",
            "WriterComponent", "RoomComponent", "ConfigComponent"
        )) {
            val entry = assertNotNull(byName[expected], "$expected must be registered; got ${byName.keys}")
            assertTrue(
                entry["sections"]!!.jsonArray.isNotEmpty(),
                "$expected must advertise at least one section"
            )
        }

        assertTrue(
            index["monitor"]!!.jsonObject["watched"]!!.jsonArray.isEmpty(),
            "no debug tap may be armed when nobody is streaming"
        )
    }

    @Test
    fun `every component reports a truthful non-negative mailbox depth`() = withFixture { fx ->
        val components = fx.get("/diagnose").bodyAsJson().jsonObject["components"]!!.jsonArray

        components.forEach { component ->
            val summary = fx.get("/diagnose?actor=${component.jsonObject["name"]!!.jsonPrimitive.content}")
                .bodyAsJson().jsonObject
            val depth = summary["mailboxDepth"]!!.jsonPrimitive.int
            assertTrue(depth >= 0, "mailbox depth must never go negative: $summary")
        }
    }

    @Test
    fun `diagnose explains a recording room with its state machine and resume mark`() = withFixture { fx ->
        fx.ready()
        fx.startRecording()

        val scheduler = fx.get("/diagnose?actor=SchedulerComponent&section=entries&room=1001")
            .bodyAsJson().jsonObject
        val schedulerEntry = scheduler["entries"]!!.jsonArray.single().jsonObject
        assertEquals(1001L, schedulerEntry["roomId"]!!.jsonPrimitive.content.toLong())
        assertEquals("Recording", schedulerEntry["fsm"]!!.jsonObject["state"]!!.jsonPrimitive.content)

        val session = fx.get("/diagnose?actor=SessionComponent&section=entries&room=1001")
            .bodyAsJson().jsonObject
        val sessionEntry = session["entries"]!!.jsonArray.single().jsonObject
        assertEquals("Recording", sessionEntry["fsm"]!!.jsonObject["state"]!!.jsonPrimitive.content)
        assertTrue(
            sessionEntry["segmentIndex"]!!.jsonPrimitive.int > 0,
            "a recording session must have queued segments: $sessionEntry"
        )
        assertTrue(
            sessionEntry["playlistLoopRunning"]!!.jsonPrimitive.content.toBoolean(),
            "the playlist poll loop must be alive while recording"
        )
        assertTrue(
            sessionEntry["noProgressMs"]!!.jsonPrimitive.content.toLong() >= 0,
            "the no-progress clock is the field that exposes a stalled recording"
        )
    }

    @Test
    fun `diagnose exposes the transition history that led to the current state`() = withFixture { fx ->
        fx.ready()
        fx.startRecording()

        val body = fx.get("/diagnose?actor=SchedulerComponent&section=history&room=1001")
            .bodyAsJson().jsonObject
        val history = body["history"]!!.jsonObject["1001"]!!.jsonArray.map { it.jsonObject }

        assertTrue(history.isNotEmpty(), "a room that went armed -> preconfig -> recording has history")
        val eventNames = history.map { it["event"]!!.jsonPrimitive.content }
        assertTrue(
            eventNames.contains("PreconfigDone"),
            "the recording transition must be visible in history: $eventNames"
        )
        assertTrue(
            history.first().containsKey("from") && history.first().containsKey("target"),
            "each record must carry from/event/target: ${history.first()}"
        )
    }

    @Test
    fun `an unknown actor is reported as not found`() = withFixture { fx ->
        val response = fx.get("/diagnose?actor=NoSuchComponent")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("SchedulerComponent"), "the error lists the known actors")
    }

    @Test
    fun `diagnose never leaks credentials`() = withFixture { fx ->
        fx.ready()
        fx.startRecording()

        val entries = fx.get("/diagnose?actor=SchedulerComponent&section=entries")
            .bodyAsJson().jsonObject["entries"]!!.jsonArray
        val playlistPath = entries.first().jsonObject["playlistPath"]!!.jsonPrimitive.content

        assertTrue(playlistPath.isNotEmpty(), "the resolved playlist is reported")
        assertTrue(
            !playlistPath.contains("?"),
            "the playlist URL must be reduced to its path (psch/pkey/aclAuth live in the query): $playlistPath"
        )
    }
}

/** The debug stream's own validation is testable without holding the (infinite) stream open. */
class DebugStreamRouteTest {

    @Test
    fun `an unknown stream type is rejected`() = withFixture { fx ->
        val response = fx.get("/debug/stream?types=nonsense")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("request"), "the error lists the valid categories")
    }

    /**
     * The taps exist to be cheap when nobody watches, which only holds if a client that goes away
     * actually releases them. An idle connection is invisible to the server, so the heartbeat write
     * is what discovers the disconnect — without it the taps would stay armed for the life of the
     * process.
     */
    @Test
    fun `the stream tap is released when the client disconnects`() = withFixture { fx ->
        val streaming = fx.scope.launch { runCatching { fx.get("/debug/stream?types=request") } }
        try {
            fx.await(5.seconds, "the request tap to arm") { armedWatched(fx) }
        } finally {
            streaming.cancelAndJoin()
        }

        val released = fx.await(5.seconds, "the request tap to be released") { releasedWatched(fx) }
        assertTrue(released.isEmpty(), "every tap must be released once the client is gone: $released")
    }

    /** Non-null while the tap is armed, so [XhrecIntegrationFixture.await] keeps polling until then. */
    private suspend fun armedWatched(fx: XhrecIntegrationFixture): List<String>? =
        watched(fx).takeIf { "request" in it }

    /** Non-null once every tap is released. */
    private suspend fun releasedWatched(fx: XhrecIntegrationFixture): List<String>? =
        watched(fx).takeIf { it.isEmpty() }

    private suspend fun watched(fx: XhrecIntegrationFixture): List<String> =
        fx.get("/diagnose").bodyAsJson().jsonObject["monitor"]!!.jsonObject["watched"]!!
            .jsonArray.map { it.jsonPrimitive.content }
}

class LogLevelEndpointTest {

    @Test
    fun `the log level can be read, changed and rejected`() = withFixture { fx ->
        val initial = fx.get("/log/level").bodyAsJson().jsonObject
        val initialLevel = initial["level"]!!.jsonPrimitive.content
        val levels = initial["levels"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(levels.containsAll(listOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF")), "$levels")

        try {
            val applied = fx.postForm("/log/level", mapOf("level" to "warn")).bodyAsText()
            assertEquals("WARN", applied, "the level is normalized to its canonical spelling")

            val now = fx.get("/log/level").bodyAsJson().jsonObject["level"]!!.jsonPrimitive.content
            assertEquals("WARN", now, "the effective level is read back from logback")
        } finally {
            fx.postForm("/log/level", mapOf("level" to initialLevel))
        }

        val rejected = fx.postForm("/log/level", mapOf("level" to "LOUD"))
        assertEquals(HttpStatusCode.BadRequest, rejected.status)
    }
}

private suspend fun io.ktor.client.statement.HttpResponse.bodyAsJson() =
    Json.parseToJsonElement(bodyAsText())
