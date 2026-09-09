package github.rikacelery.v3.components

import github.rikacelery.v3.api.ApiClient
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.events.CommandAck
import github.rikacelery.v3.events.CommandEnvelope
import github.rikacelery.v3.events.OkResponse
import github.rikacelery.v3.events.RefreshRoomCmd
import github.rikacelery.v3.events.RoomStatusChanged
import github.rikacelery.v3.hooks.EventHook
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.time.Duration

class RoomComponentTest {

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `refresh publishes changed status before acknowledging request`() = runTest(UnconfinedTestDispatcher()) {
        val mockServer = HttpClient(MockEngine { request ->
            assertEquals("https://mock.platform/broadcasts/model", request.url.toString())
            respond(
                content = """{"item":{"status":"p2p"}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        })
        val eventBus = EventBus()
        val requestBus = RequestBus(eventBus, backgroundScope)
        val observed = mutableListOf<Any>()
        eventBus.installHook(object : EventHook {
            override suspend fun intercept(event: Any): Any? {
                observed += event
                return event
            }
        })

        val component = RoomComponent(
            apiClient = ApiClient(),
            listConfPath = Files.createTempFile("xhrec-room-test", ".conf").toString(),
            requestBus = requestBus,
            eventBus = eventBus,
            parentScope = backgroundScope,
            refreshInterval = Duration.INFINITE,
            roomStatusFetcher = { roomName ->
                mockServer.get("https://mock.platform/broadcasts/$roomName").bodyAsText()
                    .substringAfter("\"status\":\"").substringBefore('"')
            }
        )
        component.start()
        advanceUntilIdle()
        component.internalAdd(1, "model", "highest", Duration.INFINITE, 0, false, false)
        eventBus.publish(RoomStatusChanged(1, "", "public"))
        advanceUntilIdle()
        observed.clear()

        component.handle(HandleRoomCommand(CommandEnvelope(1, RefreshRoomCmd(1))))
        advanceUntilIdle()

        assertEquals(
            listOf(
                RoomStatusChanged(1, "public", "p2p"),
                CommandAck(1, OkResponse)
            ),
            observed
        )
        component.stop()
        mockServer.close()
    }
}
