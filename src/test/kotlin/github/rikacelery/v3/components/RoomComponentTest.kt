package github.rikacelery.v3.components

import github.rikacelery.v3.api.ApiClient
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.data.FavoriteCandidate
import github.rikacelery.v3.data.RoomSettings
import github.rikacelery.v3.data.RuntimeTuning
import github.rikacelery.v3.data.User
import github.rikacelery.v3.events.CommandAck
import github.rikacelery.v3.events.CommandEnvelope
import github.rikacelery.v3.events.OkResponse
import github.rikacelery.v3.events.PersistConfig
import github.rikacelery.v3.events.RefreshRoomCmd
import github.rikacelery.v3.events.RoomAdded
import github.rikacelery.v3.events.RoomStatusChanged
import github.rikacelery.v3.hooks.EventHook
import github.rikacelery.v3.utils.HttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
            runtimeTuning = RuntimeTuning(roomPollInterval = Duration.INFINITE),
            roomStatusFetcher = { roomName ->
                mockServer.get("https://mock.platform/broadcasts/$roomName").bodyAsText()
                    .substringAfter("\"status\":\"").substringBefore('"')
            }
        )
        component.start()
        advanceUntilIdle()
        component.internalAdd(1, "model", RoomSettings())
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

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `favorites candidates carry the room name and flag the rooms that already exist`() = runTest(UnconfinedTestDispatcher()) {
        val requested = mutableListOf<String>()
        val platform = HttpClient(MockEngine { request ->
            requested += request.url.encodedPath
            when (request.url.encodedPath) {
                "/api/front/users/42/favorites" -> json("""{"modelIds":[1001,1002,1003]}""")
                "/api/front/v2/models/1002/cam" -> json("""{"user":{"user":{"username":"model-b"}}}""")
                "/api/front/v2/models/1003/cam" -> json("""{"user":{"user":{"username":"model-c"}}}""")
                else -> respond("{}", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
        })
        val eventBus = EventBus()
        val requestBus = RequestBus(eventBus, backgroundScope)
        val component = RoomComponent(
            apiClient = ApiClient(listOf("mock.platform"), stubProvider(platform)) { "https://$it" },
            listConfPath = Files.createTempFile("xhrec-favorites", ".conf").toString(),
            requestBus = requestBus,
            eventBus = eventBus,
            parentScope = backgroundScope,
            runtimeTuning = RuntimeTuning(roomPollInterval = Duration.INFINITE),
            roomStatusFetcher = { "off" }
        )
        component.start()
        advanceUntilIdle()
        component.internalAdd(1001, "model-a", RoomSettings(quality = "720p"))

        val candidates = component.favoriteCandidates(listOf(User("cookie-1", 42L, "tester", 100L)))
        advanceUntilIdle()

        assertEquals(
            listOf(
                FavoriteCandidate(1001, "model-a", existing = true),
                FavoriteCandidate(1002, "model-b", existing = false),
                FavoriteCandidate(1003, "model-c", existing = false)
            ),
            candidates,
            "candidates are listed by name and mark the rooms that already exist"
        )
        assertFalse(
            requested.contains("/api/front/v2/models/1001/cam"),
            "an existing room already knows its name: $requested"
        )
        component.stop()
        platform.close()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `favorites import adds only the picked models and leaves them disarmed`() = runTest(UnconfinedTestDispatcher()) {
        val requested = mutableListOf<String>()
        val platform = HttpClient(MockEngine { request ->
            requested += request.url.encodedPath
            when (request.url.encodedPath) {
                "/api/front/v2/models/1002/cam" -> json("""{"user":{"user":{"username":"model-two"}}}""")
                "/api/front/v2/models/1003/cam" -> json("""{"user":{"user":{"username":"model-three"}}}""")
                else -> respond("{}", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
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
            apiClient = ApiClient(listOf("mock.platform"), stubProvider(platform)) { "https://$it" },
            listConfPath = Files.createTempFile("xhrec-favorites-import", ".conf").toString(),
            requestBus = requestBus,
            eventBus = eventBus,
            parentScope = backgroundScope,
            runtimeTuning = RuntimeTuning(roomPollInterval = Duration.INFINITE),
            roomStatusFetcher = { "off" }
        )
        component.start()
        advanceUntilIdle()
        component.internalAdd(1001, "model-one", RoomSettings(quality = "720p"))
        observed.clear()

        // the user picked 1002 and 1003; 1001 is already a room and must stay untouched
        val added = component.importFavorites(listOf(1001, 1002, 1003))
        advanceUntilIdle()

        assertEquals(listOf("model-two", "model-three"), added)
        assertEquals(
            listOf(RoomAdded(1002, "model-two"), RoomAdded(1003, "model-three")),
            observed.filterIsInstance<RoomAdded>()
        )
        assertTrue(observed.any { it is PersistConfig }, "imported rooms are persisted")
        assertFalse(requested.contains("/api/front/v2/models/1001/cam"), "existing rooms are not touched: $requested")
        component.stop()
        platform.close()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `favorites import tolerates a rejected request and models without a name`() = runTest(UnconfinedTestDispatcher()) {
        val platform = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/front/users/42/favorites" -> respond(
                    """{"error":"User is unauthorized","data":[]}""",
                    HttpStatusCode.Forbidden,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
                "/api/front/users/43/favorites" -> json("""{"modelIds":[1004]}""")
                else -> respond("{}", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
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
            apiClient = ApiClient(listOf("mock.platform"), stubProvider(platform)) { "https://$it" },
            listConfPath = Files.createTempFile("xhrec-favorites-fail", ".conf").toString(),
            requestBus = requestBus,
            eventBus = eventBus,
            parentScope = backgroundScope,
            runtimeTuning = RuntimeTuning(roomPollInterval = Duration.INFINITE),
            roomStatusFetcher = { "off" }
        )
        component.start()
        advanceUntilIdle()

        val candidates = component.favoriteCandidates(
            listOf(User("expired", 42L, "tester", 100L), User("cookie-2", 43L, "second", 100L))
        )
        val added = component.importFavorites(listOf(1004))
        advanceUntilIdle()

        assertEquals(emptyList<FavoriteCandidate>(), candidates, "a rejected account contributes nothing")
        assertEquals(emptyList<String>(), added, "a favorite without a name must not create a room")
        assertTrue(observed.filterIsInstance<RoomAdded>().isEmpty())
        component.stop()
        platform.close()
    }
}

private fun MockRequestHandleScope.json(body: String) = respond(
    body,
    HttpStatusCode.OK,
    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
)

private fun stubProvider(client: HttpClient) = object : HttpClientProvider {
    override fun direct(key: String, http1: Boolean, expectSuccess: Boolean): HttpClient = client
    override fun proxied(key: String, http1: Boolean, expectSuccess: Boolean): HttpClient = client
}
