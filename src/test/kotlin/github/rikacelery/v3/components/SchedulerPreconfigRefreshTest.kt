package github.rikacelery.v3.components

import github.rikacelery.v3.api.ApiClient
import github.rikacelery.v3.core.DataChannel
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.data.RoomSettings
import github.rikacelery.v3.data.RuntimeTuning
import github.rikacelery.v3.events.CommandAck
import github.rikacelery.v3.events.CommandEnvelope
import github.rikacelery.v3.events.ConfigResponse
import github.rikacelery.v3.events.DecryptKeyMatch
import github.rikacelery.v3.events.GetDecryptKey
import github.rikacelery.v3.events.GetRoomConfig
import github.rikacelery.v3.events.MatchDecryptKeys
import github.rikacelery.v3.events.OkResponse
import github.rikacelery.v3.events.RefreshRoomCmd
import github.rikacelery.v3.events.RoomConfigResponse
import github.rikacelery.v3.events.RoomStatusChanged
import github.rikacelery.v3.hooks.EventHook
import github.rikacelery.v3.m3u8.M3u8Parser
import github.rikacelery.v3.utils.CdnSelector
import github.rikacelery.v3.utils.HttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.coroutines.coroutineContext
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A 403/404 from the variant playlist is the one preconfig failure that is not really about the
 * network: the platform handed out a token, so the CDN refusing the playlist means the room moved
 * on (the show ended, the room went offline, the private show was replaced). Left alone, the room
 * keeps the stale status it was armed with and the preconfig loop retries a stream that no longer
 * exists, which is exactly what the reported log shows:
 *
 * ```
 * WARN v3.SchedulerEntry - Preconfig failed room=…: playlist unusable (Client request(…403…))
 * ```
 *
 * The fix asks the RoomComponent to re-read the room on 403/404 (the same [RefreshRoomCmd] a live
 * session sends when its playlist turns 403/404). A status that really changed comes back as
 * [RoomStatusChanged], and `Preconfiguring` re-arms the room once it is no longer recordable.
 */
class SchedulerPreconfigRefreshTest {

    @Test
    fun `a 404 on the variant playlist asks the room component to refresh it`() = runBlocking {
        withProbe(HttpStatusCode.NotFound) { h ->
            assertTrue(
                awaitUntil { h.refreshes.get() > 0 },
                "a 404 must ask RoomComponent to re-read the room (state=${h.entry.fsm.currentState})"
            )
            assertTrue(
                awaitUntil { h.entry.lastFailReason == "playlist unusable (HTTP 404)" },
                "a 404 is reported as an HTTP status, not a raw client message: ${h.entry.lastFailReason}"
            )
        }
    }

    @Test
    fun `a 403 on the variant playlist asks the room component to refresh it`() = runBlocking {
        withProbe(HttpStatusCode.Forbidden) { h ->
            assertTrue(
                awaitUntil { h.refreshes.get() > 0 },
                "a 403 must ask RoomComponent to re-read the room (state=${h.entry.fsm.currentState})"
            )
            assertTrue(
                awaitUntil { h.entry.lastFailReason == "playlist unusable (HTTP 403)" },
                "a 403 is reported as an HTTP status, not a raw client message: ${h.entry.lastFailReason}"
            )
        }
    }

    @Test
    fun `other playlist failures do not ask for a room refresh`() = runBlocking {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.InternalServerError)) {
            withProbe(status) { h ->
                assertTrue(
                    awaitUntil { h.entry.lastFailReason?.startsWith("playlist unusable") == true },
                    "the probe must fail for HTTP ${status.value}"
                )
                assertEquals(
                    0,
                    h.refreshes.get(),
                    "HTTP ${status.value} is not a room-state signal, so the room must not be re-read"
                )
            }
        }
    }

    @Test
    fun `the refreshed status that ends the show re-arms the room and stops the loop`() = runBlocking {
        withProbe(HttpStatusCode.NotFound) { h ->
            assertTrue(awaitUntil { h.refreshes.get() > 0 }, "the rejected playlist must trigger the refresh")

            // the RoomComponent answers a refresh by publishing RoomStatusChanged when, and only
            // when, the platform reports a different status
            h.eventBus.publish(RoomStatusChanged(7, "public", "off"))

            assertTrue(
                awaitUntil { h.entry.fsm.currentState == SchedulerState.Armed },
                "a room that stopped being recordable must leave Preconfiguring (state=${h.entry.fsm.currentState})"
            )
            assertTrue(
                awaitUntil { !h.entry.preconfigLoop.isRunning },
                "re-arming the room must stop the preconfig loop instead of retrying a dead stream"
            )
            assertFalse(h.entry.preconfigLoop.isRunning)
        }
    }

    // —— harness ——

    private class Harness(
        val scheduler: SchedulerComponent,
        val entry: SchedulerEntry,
        val eventBus: EventBus,
        val refreshes: AtomicInteger
    )

    /**
     * Runs one preconfig attempt against a mock CDN that answers [playlistStatus] for the selected
     * variant playlist. Everything else (broadcast info, master playlist, decrypt keys) succeeds,
     * so the room reaches the probe.
     */
    private suspend fun withProbe(playlistStatus: HttpStatusCode, block: suspend (Harness) -> Unit) {
        val refreshes = AtomicInteger()
        val cdn = HttpClient(MockEngine { request ->
            when {
                request.url.toString().endsWith("/api/front/v1/broadcasts/model") ->
                    respond("""{"item":{"status":"public"}}""", headers = jsonHeaders)

                request.url.encodedPath.startsWith("/master/") -> respond(masterPlaylist)

                request.url.encodedPath == "/media/model.m3u8" ->
                    respond("rejected", playlistStatus)

                else -> error("unexpected request ${request.url}")
            }
        }) { expectSuccess = true }
        val provider = object : HttpClientProvider {
            override fun direct(key: String, http1: Boolean, expectSuccess: Boolean): HttpClient = cdn
            override fun proxied(key: String, http1: Boolean, expectSuccess: Boolean): HttpClient = cdn
        }
        val eventBus = EventBus()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val requestBus = RequestBus(eventBus, scope)
        eventBus.installHook(object : EventHook {
            override suspend fun intercept(event: Any): Any? {
                if (event is CommandEnvelope) {
                    val answer = when (event.command) {
                        is GetRoomConfig -> RoomConfigResponse(RoomSettings(pkey = "room-key"))
                        is MatchDecryptKeys -> DecryptKeyMatch("key-id", "decrypt-key")
                        is GetDecryptKey -> ConfigResponse("decrypt-key")
                        is RefreshRoomCmd -> {
                            refreshes.incrementAndGet()
                            OkResponse
                        }

                        else -> null
                    }
                    if (answer != null) eventBus.publish(CommandAck(event.id, answer))
                }
                return event
            }
        })
        val dataChannel = DataChannel()
        val downloader = DownloaderComponent(dataChannel, eventBus = eventBus, parentScope = scope)
        val session = SessionComponent(dataChannel, downloader, M3u8Parser, requestBus, eventBus, scope)
        val scheduler = SchedulerComponent(
            requestBus = requestBus,
            sessionComponent = session,
            apiClient = ApiClient(listOf("ignored.invalid"), provider) { "http://127.0.0.1:18082" },
            streamAuthKey = "stream-key",
            eventBus = eventBus,
            parentScope = scope,
            httpClientProvider = provider,
            // one attempt inside the test window; the retry behaviour has its own coverage
            runtimeTuning = RuntimeTuning(
                preconfigRetryInterval = 30.seconds,
                preconfigProbeTimeout = 3.seconds
            ),
            masterUrlCandidates = { roomId, pkey, _ ->
                listOf("http://127.0.0.1:18082/master/$roomId?pkey=$pkey")
            }
        )
        val oldCdnHosts = CdnSelector.hosts
        CdnSelector.updateHosts(emptyList())

        try {
            scheduler.start()
            val entry = scheduler.internalAdd(7, "model", RoomSettings(pkey = "room-key"), isArmed = true)!!
            scheduler.tell(
                SchedulerSignal(7, SchedulerEvent.RoomStatusChanged, SchedulerDriveData(roomStatus = "public"))
            )
            block(Harness(scheduler, entry, eventBus, refreshes))
        } finally {
            scheduler.stop()
            CdnSelector.updateHosts(oldCdnHosts)
            scope.cancel()
            cdn.close()
        }
    }

    private suspend fun awaitUntil(
        timeout: Duration = 5.seconds,
        predicate: () -> Boolean
    ): Boolean = withTimeoutOrNull(timeout) {
        while (!predicate()) delay(20.milliseconds)
        true
    } ?: false

    private companion object {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

        val masterPlaylist = """
            #EXTM3U
            #EXT-X-MOUFLON:PSCH:key-id
            #EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=640x360,FRAME-RATE=30
            http://127.0.0.1:18082/media/model.m3u8
        """.trimIndent()
    }
}
