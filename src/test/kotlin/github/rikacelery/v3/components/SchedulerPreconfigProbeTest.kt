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
import github.rikacelery.v3.events.RoomConfigResponse
import github.rikacelery.v3.hooks.EventHook
import github.rikacelery.v3.m3u8.M3u8Parser
import github.rikacelery.v3.utils.CdnSelector
import github.rikacelery.v3.utils.HttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Regression guard: the probe of the selected variant playlist runs under a watchdog. A probe
 * that outlives it is a *failed probe*, not a cancellation of its caller — the room has to stay
 * in `Preconfiguring` and retry on the next tick.
 *
 * When the watchdog result escaped as a `CancellationException` ([SchedulerEntry.preconfigSignal]
 * rethrows it) the preconfig loop cancelled itself silently: no `Preconfig failed` log, no
 * dashboard hint, no retry. A room whose CDN path stalls once therefore stayed armed forever
 * while the dashboard kept showing it as merely "listening".
 */
class SchedulerPreconfigProbeTest {

    @Test
    fun `a playlist probe that outlives its watchdog is retried instead of killing the preconfig loop`() =
        runBlocking {
            val probes = AtomicInteger()
            val client = HttpClient(MockEngine { request ->
                val url = request.url.toString()
                when {
                    url.endsWith("/api/front/v1/broadcasts/model") ->
                        respond("""{"item":{"status":"public"}}""", headers = jsonHeaders)

                    request.url.encodedPath.startsWith("/master/") -> respond(masterPlaylist)

                    request.url.encodedPath == "/media/model.m3u8" -> {
                        // the first probe stalls far past the watchdog; every later probe answers
                        if (probes.getAndIncrement() == 0) delay(30.seconds)
                        respond("#EXTM3U")
                    }

                    else -> error("unexpected request $url")
                }
            }) { expectSuccess = true }
            val provider = SingleClientProvider(client)
            val eventBus = EventBus()
            installRequestAnswers(eventBus)
            val testScope = CoroutineScope(coroutineContext + SupervisorJob())
            val requestBus = RequestBus(eventBus, testScope)
            val dataChannel = DataChannel()
            val downloader = DownloaderComponent(dataChannel, eventBus = eventBus, parentScope = testScope)
            val session = SessionComponent(dataChannel, downloader, M3u8Parser, requestBus, eventBus, testScope)
            val scheduler = SchedulerComponent(
                requestBus = requestBus,
                sessionComponent = session,
                apiClient = ApiClient(listOf("ignored.invalid"), provider) { "http://127.0.0.1:18082" },
                streamAuthKey = "stream-key",
                eventBus = eventBus,
                parentScope = testScope,
                httpClientProvider = provider,
                runtimeTuning = RuntimeTuning(
                    preconfigRetryInterval = 150.milliseconds,
                    preconfigProbeTimeout = 200.milliseconds
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

                val retried = withTimeoutOrNull(5.seconds) {
                    while (probes.get() < 2) delay(20.milliseconds)
                    true
                }

                assertEquals(
                    true,
                    retried,
                    "the preconfig loop must survive a probe that outlives its watchdog (probes=${probes.get()}, " +
                            "state=${entry.fsm.currentState})"
                )
                assertEquals(
                    SchedulerState.Recording,
                    entry.fsm.currentState,
                    "the retried probe succeeds, so the room must go on to record"
                )
            } finally {
                scheduler.stop()
                CdnSelector.updateHosts(oldCdnHosts)
                testScope.cancel()
                client.close()
            }
        }

    /**
     * The other half of the same question: a *client* timeout (Ktor/OkHttp giving up on its own
     * request budget) is an `IOException` — `HttpRequestTimeoutException` — not a cancellation, so
     * it already lands in the `catch (e: Exception) -> false` branch. This guards that end to end
     * against a real engine and a real socket that accepts but never answers.
     */
    @Test
    fun `a client request timeout is a failed probe, not a cancellation of the preconfig loop`() = runBlocking {
        val silent = ServerSocket(0)
        val connections = AtomicInteger()
        val held = Collections.synchronizedList(mutableListOf<Socket>())
        val acceptor = thread(isDaemon = true, name = "silent-playlist") {
            while (!silent.isClosed) {
                val socket = try {
                    silent.accept()
                } catch (e: Exception) {
                    break
                }
                if (connections.getAndIncrement() < 2) {
                    // accepted but never answered: each of these two probes ends in a client timeout
                    held += socket
                } else {
                    runCatching {
                        socket.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Length: 7\r\nConnection: close\r\n\r\n#EXTM3U".toByteArray()
                        )
                        socket.getOutputStream().flush()
                    }
                    runCatching { socket.close() }
                }
            }
            held.forEach { runCatching { it.close() } }
        }

        val variantUrl = "http://127.0.0.1:${silent.localPort}/media/model.m3u8"
        val timeoutClient = HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 200
                socketTimeoutMillis = 200
                connectTimeoutMillis = 500
            }
        }
        val mockClient = HttpClient(MockEngine { request ->
            val url = request.url.toString()
            when {
                url.endsWith("/api/front/v1/broadcasts/model") ->
                    respond("""{"item":{"status":"public"}}""", headers = jsonHeaders)

                request.url.encodedPath.startsWith("/master/") -> respond(masterPlaylistWith(variantUrl))

                else -> error("unexpected request $url")
            }
        }) { expectSuccess = true }
        val provider = PreconfigClientProvider(timeoutClient, mockClient)
        val eventBus = EventBus()
        installRequestAnswers(eventBus)
        val testScope = CoroutineScope(coroutineContext + SupervisorJob())
        val requestBus = RequestBus(eventBus, testScope)
        val dataChannel = DataChannel()
        val downloader = DownloaderComponent(dataChannel, eventBus = eventBus, parentScope = testScope)
        val session = SessionComponent(dataChannel, downloader, M3u8Parser, requestBus, eventBus, testScope)
        val scheduler = SchedulerComponent(
            requestBus = requestBus,
            sessionComponent = session,
            apiClient = ApiClient(listOf("ignored.invalid"), provider) { "http://127.0.0.1:18082" },
            streamAuthKey = "stream-key",
            eventBus = eventBus,
            parentScope = testScope,
            httpClientProvider = provider,
            runtimeTuning = RuntimeTuning(
                preconfigRetryInterval = 150.milliseconds,
                // far beyond the client's own budget: the client timeout is the one that fires
                preconfigProbeTimeout = 10.seconds
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

            withTimeoutOrNull(10.seconds) {
                while (entry.fsm.currentState != SchedulerState.Recording) delay(20.milliseconds)
            }

            assertEquals(
                SchedulerState.Recording,
                entry.fsm.currentState,
                "a client request timeout is a failed probe: the loop must retry and then record " +
                        "(connections=${connections.get()})"
            )
        } finally {
            scheduler.stop()
            CdnSelector.updateHosts(oldCdnHosts)
            testScope.cancel()
            timeoutClient.close()
            mockClient.close()
            silent.close()
        }
    }

    private fun installRequestAnswers(eventBus: EventBus) {
        eventBus.installHook(object : EventHook {
            override suspend fun intercept(event: Any): Any? {
                if (event is CommandEnvelope) {
                    val answer = when (event.command) {
                        is GetRoomConfig -> RoomConfigResponse(RoomSettings(pkey = "room-key"))
                        is MatchDecryptKeys -> DecryptKeyMatch("key-id", "decrypt-key")
                        is GetDecryptKey -> ConfigResponse("decrypt-key")
                        else -> null
                    }
                    if (answer != null) eventBus.publish(CommandAck(event.id, answer))
                }
                return event
            }
        })
    }

    private class SingleClientProvider(private val client: HttpClient) : HttpClientProvider {
        override fun direct(key: String, http1: Boolean, expectSuccess: Boolean): HttpClient = client
        override fun proxied(key: String, http1: Boolean, expectSuccess: Boolean): HttpClient = client
    }

    /** Real engine for the playlist probe, mock engine for everything else. */
    private class PreconfigClientProvider(
        private val preconfigClient: HttpClient,
        private val otherClient: HttpClient
    ) : HttpClientProvider {
        override fun direct(key: String, http1: Boolean, expectSuccess: Boolean): HttpClient = otherClient
        override fun proxied(key: String, http1: Boolean, expectSuccess: Boolean): HttpClient =
            if (key.startsWith("preconfig_")) preconfigClient else otherClient
    }

    private companion object {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

        fun masterPlaylistWith(variantUrl: String) = """
            #EXTM3U
            #EXT-X-MOUFLON:PSCH:key-id
            #EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=640x360,FRAME-RATE=30
            $variantUrl
        """.trimIndent()

        val masterPlaylist = masterPlaylistWith("http://127.0.0.1:18082/media/model.m3u8")
    }
}
