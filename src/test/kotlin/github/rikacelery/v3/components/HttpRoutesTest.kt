package github.rikacelery.v3.components

import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.data.Room
import github.rikacelery.v3.events.ActivateRecordingCmd
import github.rikacelery.v3.events.AddRoom
import github.rikacelery.v3.events.CommandAck
import github.rikacelery.v3.events.CommandEnvelope
import github.rikacelery.v3.events.DeactivateCmd
import github.rikacelery.v3.events.GetRooms
import github.rikacelery.v3.events.OkResponse
import github.rikacelery.v3.events.RoomNameResponse
import github.rikacelery.v3.hooks.EventHook
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Exercises the production control routes through Ktor's test host, so route wiring,
 * command ordering, and injected delays are covered without opening a real socket.
 */
class HttpRoutesTest {

    @Test
    fun `active add issues AddRoom before ActivateRecordingCmd`() = testApplication {
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

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("Room added: model", response.bodyAsText())
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
    fun `restart deactivates then reactivates using injected delay`() = testApplication {
        val harness = RouteHarness(restartDelay = 1.milliseconds)
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

    private class RouteHarness(restartDelay: Duration = 500.milliseconds) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val eventBus = EventBus()
        val requestBus = RequestBus(eventBus, scope)
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
            eventBus = eventBus,
            requestBus = requestBus,
            metricComponent = MetricComponent(eventBus, scope),
            postProcessorComponent = PostProcessorComponent(eventBus, scope),
            scope = scope,
            restartDelay = restartDelay
        )

        fun close() {
            scope.cancel()
        }
    }
}
