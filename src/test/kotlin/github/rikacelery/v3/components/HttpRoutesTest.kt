package github.rikacelery.v3.components

import github.rikacelery.v3.integration.testApplicationWithBudget
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.data.Room
import github.rikacelery.v3.data.RuntimeTuning
import github.rikacelery.v3.events.ActivateRecordingCmd
import github.rikacelery.v3.events.AddRoom
import github.rikacelery.v3.events.CommandAck
import github.rikacelery.v3.events.CommandEnvelope
import github.rikacelery.v3.events.DeactivateCmd
import github.rikacelery.v3.events.GetArmedRoomIds
import github.rikacelery.v3.events.GetPreconfiguringRoomIds
import github.rikacelery.v3.events.GetRecordingHints
import github.rikacelery.v3.events.GetRoomDetailedStatus
import github.rikacelery.v3.events.GetRooms
import github.rikacelery.v3.events.GetSessions
import github.rikacelery.v3.events.OkResponse
import github.rikacelery.v3.events.RecordingHintsResponse
import github.rikacelery.v3.events.RoomNameResponse
import github.rikacelery.v3.hooks.EventHook
import github.rikacelery.v3.utils.ModelSchedule
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Exercises the production control routes through Ktor's test host, so route wiring,
 * command ordering, and injected delays are covered without opening a real socket.
 */
class HttpRoutesTest {

    @Test
    fun `active add issues AddRoom before ActivateRecordingCmd`() = testApplicationWithBudget {
        val harness = RouteHarness()
        try {
            harness.eventBus.installHook(object : EventHook {
                override suspend fun intercept(event: Any): Any? {
                    if (event is CommandEnvelope) {
                        harness.commands += event.command
                        when (val cmd = event.command) {
                            is AddRoom -> harness.eventBus.publish(
                                CommandAck(event.id, RoomNameResponse(cmd.name))
                            )

                            is GetRooms -> harness.eventBus.publish(
                                CommandAck(event.id, listOf(harness.room))
                            )

                            else -> harness.eventBus.publish(CommandAck(event.id, OkResponse))
                        }
                    }
                    return event
                }
            })
            application { harness.server.installApplication(this, stopEngine = {}) }

            val response = client.get("/add?name=model&active=true")
            val body = response.bodyAsText()

            // The body carries the route's own message ("Error: Request … timed out after …"), so a
            // non-200 says what failed instead of only that it did.
            assertEquals(HttpStatusCode.OK, response.status, body)
            assertEquals("Room added: model", body)
            assertEquals(
                listOf(AddRoom::class, GetRooms::class, ActivateRecordingCmd::class),
                harness.commands.map { it::class }
            )
            assertTrue(
                harness.commands.indexOfFirst { it is AddRoom } <
                    harness.commands.indexOfFirst { it is ActivateRecordingCmd }
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `restart deactivates then reactivates using injected delay`() = testApplicationWithBudget {
        val harness = RouteHarness(RuntimeTuning(httpRestartDelay = 1.milliseconds))
        try {
            harness.eventBus.installHook(object : EventHook {
                override suspend fun intercept(event: Any): Any? {
                    if (event is CommandEnvelope) {
                        harness.commands += event.command
                        harness.eventBus.publish(CommandAck(event.id, OkResponse))
                    }
                    return event
                }
            })
            application { harness.server.installApplication(this, stopEngine = {}) }

            val response = client.get("/restart?id=1001")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("Restarted", response.bodyAsText())
            assertEquals(listOf(DeactivateCmd::class, ActivateRecordingCmd::class), harness.commands.map { it::class })
        } finally {
            harness.close()
        }
    }

    @Test
    fun `the dashboard page ships the room settings dialog`() = testApplicationWithBudget {
        val harness = RouteHarness()
        try {
            application { harness.server.installApplication(this, stopEngine = {}) }

            val response = client.get("/")

            assertEquals(HttpStatusCode.OK, response.status)
            val html = response.bodyAsText()
            assertTrue(html.contains("Room Settings"), "the dashboard must carry the room settings dialog")
            assertTrue(html.contains("/filter?id="), "the dialog must call the recording filter route")
        } finally {
            harness.close()
        }
    }

    /**
     * Recording may only be shown while it is actually happening. A session from an earlier
     * incarnation can still be closing — and `GetSessions` still reports that as Recording — while
     * the scheduler has already moved on to preconfiguration (deactivate, then re-activate). The
     * dashboard must report the room as Listening for that window instead of claiming a recording
     * that is not running, and it must go back to Recording once preconfig is done.
     */
    @Test
    fun `the dashboard never reports a preconfiguring room as recording`() = testApplicationWithBudget {
        val harness = RouteHarness()
        val preconfiguring = AtomicReference(listOf(1001L))
        try {
            harness.eventBus.installHook(object : EventHook {
                override suspend fun intercept(event: Any): Any? {
                    if (event is CommandEnvelope) {
                        val answer = when (event.command) {
                            is GetRooms -> listOf(harness.room)
                            // the session of the previous incarnation is still closing: it maps to
                            // Recording, which is exactly what must not leak into the UI here
                            is GetSessions -> listOf(
                                RoomSession(1001, "model", "highest", SessionState.Recording, Instant.now())
                            )

                            is GetArmedRoomIds -> listOf(1001L)
                            is GetPreconfiguringRoomIds -> preconfiguring.get()
                            is GetRecordingHints -> RecordingHintsResponse(emptyMap())
                            is GetRoomDetailedStatus -> emptyMap<Long, Map<String, Any>>()
                            else -> null
                        }
                        if (answer != null) harness.eventBus.publish(CommandAck(event.id, answer))
                    }
                    return event
                }
            })
            application { harness.server.installApplication(this, stopEngine = {}) }

            suspend fun session(): JsonObject = Json.parseToJsonElement(client.get("/dashboard").bodyAsText())
                .jsonObject["listv2"]!!.jsonArray.single().jsonObject["session"]!!.jsonObject

            val duringPreconfig = session()
            assertEquals("Listening", duringPreconfig["status"]!!.jsonPrimitive.content)
            assertFalse(duringPreconfig["active"]!!.jsonPrimitive.boolean, "preconfiguration is not a recording")
            assertEquals(0L, duringPreconfig["startTime"]!!.jsonPrimitive.long)

            preconfiguring.set(emptyList())
            val recording = session()
            assertEquals("Recording", recording["status"]!!.jsonPrimitive.content)
            assertTrue(recording["active"]!!.jsonPrimitive.boolean)
        } finally {
            harness.close()
        }
    }

    /**
     * The schedule endpoint's own JSON, not a copy of it built inside the test.
     *
     * It replaces a test that rebuilt this response shape by hand and then asserted on its own
     * `buildJsonObject` output, which could only fail if the JSON builder itself broke.
     */
    @Test
    fun `the schedule endpoint reports the hours the room was seen live`() = testApplicationWithBudget {
        val harness = RouteHarness()
        try {
            application { harness.server.installApplication(this, stopEngine = {}) }
            ModelSchedule.reset()
            val monday10 = ZonedDateTime.of(2024, 1, 15, 10, 0, 0, 0, ZoneId.systemDefault())
                .toInstant().toEpochMilli()
            val monday14 = ZonedDateTime.of(2024, 1, 15, 14, 0, 0, 0, ZoneId.systemDefault())
                .toInstant().toEpochMilli()
            repeat(3) { ModelSchedule.record(1001L, monday10) }
            ModelSchedule.record(1001L, monday14)

            val response = client.get("/model/schedule?id=1001")
            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.OK, response.status, body)
            val json = Json.parseToJsonElement(body).jsonObject

            assertEquals(1001L, json["roomId"]!!.jsonPrimitive.long)
            assertEquals(4, json["totalRecordings"]!!.jsonPrimitive.int)
            assertEquals(24, json["hourDistribution"]!!.jsonArray.size)
            // hour 10 was seen three times of four, so it must lead
            assertEquals(10, json["topHours"]!!.jsonArray.first().jsonObject["hour"]!!.jsonPrimitive.int)
        } finally {
            ModelSchedule.reset()
            harness.close()
        }
    }

    private class RouteHarness(runtimeTuning: RuntimeTuning = RuntimeTuning()) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val eventBus = EventBus()
        // The 5 s default is a production choice about a real deployment; these tests assert route
        // wiring and command order against an in-process fake, and on a loaded machine a reply that
        // normally takes microseconds can miss that window — which surfaced as an unrelated 500.
        val requestBus = RequestBus(eventBus, scope, defaultTimeoutMs = 30_000)
        val commands = CopyOnWriteArrayList<Any>()
        val room = Room(
            id = 1001,
            name = "model",
            quality = "highest",
            timeLimit = Duration.INFINITE,
            sizeLimitBytes = 0,
            lastSeen = null,
            status = "off"
        )
        val server = HttpServerComponent(
            port = 0,
            tls = true,
            eventBus = eventBus,
            requestBus = requestBus,
            metricComponent = MetricComponent(eventBus, scope),
            postProcessorComponent = PostProcessorComponent(eventBus, scope),
            scope = scope,
            runtimeTuning = runtimeTuning
        )

        fun close() {
            scope.cancel()
        }
    }
}
