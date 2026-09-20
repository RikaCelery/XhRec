package github.rikacelery.v3.components

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeoutOrNull
import github.rikacelery.v3.integration.TEST_BUDGET
import github.rikacelery.v3.api.ApiClient
import github.rikacelery.v3.core.DataChannel
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.data.Hosts
import github.rikacelery.v3.data.StreamData
import github.rikacelery.v3.data.RoomSettings
import github.rikacelery.v3.data.RuntimeTuning
import github.rikacelery.v3.events.CommandAck
import github.rikacelery.v3.events.CommandEnvelope
import github.rikacelery.v3.events.ConfigResponse
import github.rikacelery.v3.events.DecryptKeyMatch
import github.rikacelery.v3.events.Download
import github.rikacelery.v3.events.GetDecryptKey
import github.rikacelery.v3.events.GetRoomConfig
import github.rikacelery.v3.events.MatchDecryptKeys
import github.rikacelery.v3.events.RoomConfigResponse
import github.rikacelery.v3.events.Segment
import github.rikacelery.v3.events.WsDisconnected
import github.rikacelery.v3.hooks.EventHook
import github.rikacelery.v3.m3u8.M3u8Parser
import github.rikacelery.v3.utils.CdnSelector
import github.rikacelery.v3.utils.HttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeInjectionTest {

    @Test
    fun `room polling and websocket refresh use injected timing`() = runTest(UnconfinedTestDispatcher(), timeout = TEST_BUDGET) {
        val requests = CopyOnWriteArrayList<String>()
        val requestEvents = Channel<String>(Channel.UNLIMITED)
        val client = HttpClient(MockEngine { request ->
            requests += request.url.toString()
            requestEvents.trySend(request.url.toString())
            respond(
                content = """{"item":{"status":"off"}}""",
                headers = jsonHeaders
            )
        })
        val provider = RecordingProvider(client, client)
        val eventBus = EventBus()
        val component = RoomComponent(
            apiClient = ApiClient(listOf("ignored.invalid"), provider) { "http://127.0.0.1:18080" },
            listConfPath = Files.createTempFile("xhrec-runtime-room", ".conf").toString(),
            requestBus = RequestBus(eventBus, backgroundScope),
            eventBus = eventBus,
            parentScope = backgroundScope,
            runtimeTuning = RuntimeTuning(
                roomPollInterval = 20.milliseconds,
                roomRefreshDebounce = 5.milliseconds
            )
        )

        try {
            component.internalAdd(7, "model", RoomSettings())
            component.start()
            runCurrent()
            assertEquals("http://127.0.0.1:18080/api/front/v1/broadcasts/model", requestEvents.receive())
            assertEquals(1, requests.size)

            advanceTimeBy(19)
            runCurrent()
            assertEquals(1, requests.size)
            advanceTimeBy(1)
            runCurrent()
            requestEvents.receive()
            assertEquals(2, requests.size)

            eventBus.publish(WsDisconnected)
            runCurrent()
            advanceTimeBy(4)
            runCurrent()
            assertEquals(2, requests.size)
            advanceTimeBy(1)
            runCurrent()
            requestEvents.receive()
            assertEquals(3, requests.size)
            assertTrue(requests.all { it == "http://127.0.0.1:18080/api/front/v1/broadcasts/model" })
        } finally {
            component.stop()
            client.close()
        }
    }

    @Test
    fun `live events use injected websocket client url and reconnect timing`() = runTest(timeout = TEST_BUDGET) {
        val client = HttpClient(MockEngine {
            respond("unavailable", HttpStatusCode.ServiceUnavailable)
        })
        val provider = RecordingProvider(client, client)
        val oldHosts = Hosts.current
        Hosts.current = oldHosts.copy(webSocketHosts = listOf("ws.injected.test"))
        val builtFor = CopyOnWriteArrayList<String>()
        val builtUrls = CopyOnWriteArrayList<String>()
        val component = LiveEventSource(
            tokenProvider = { "token" },
            eventBus = EventBus(),
            parentScope = backgroundScope,
            wsPoolCount = 1,
            httpClientProvider = provider,
            runtimeTuning = RuntimeTuning(
                webSocketReconnectInitial = 2.milliseconds,
                webSocketReconnectMax = 3.milliseconds
            ),
            // the pools run off Default in production; virtual time drives them here instead
            poolDispatcher = StandardTestDispatcher(testScheduler),
            wsUrlBuilder = { host ->
                builtFor += host
                "ws://127.0.0.1:18081/connection/websocket?source=$host".also(builtUrls::add)
            }
        )

        try {
            component.start()
            runCurrent()
            assertEquals(listOf("event_0"), provider.proxiedCalls.map { it.key })
            assertEquals(listOf("ws.injected.test"), builtFor)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, provider.proxiedCalls.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, provider.proxiedCalls.size)
            assertEquals(listOf("ws.injected.test", "ws.injected.test"), builtFor)
            assertEquals(
                listOf(
                    "ws://127.0.0.1:18081/connection/websocket?source=ws.injected.test",
                    "ws://127.0.0.1:18081/connection/websocket?source=ws.injected.test"
                ),
                builtUrls
            )
        } finally {
            component.stop()
            Hosts.current = oldHosts
            client.close()
        }
    }

    @Test
    fun `scheduler uses injected clients and records successful master fetch`() = checkSchedulerMaster(false)

    @Test
    fun `scheduler records master failure and fallback success on their respective hosts`() = checkSchedulerMaster(true)

    private fun checkSchedulerMaster(failPrimary: Boolean) = runBlocking {
        val testScope = CoroutineScope(coroutineContext + SupervisorJob())
        val requests = CopyOnWriteArrayList<String>()
        val client = HttpClient(MockEngine { request ->
            val url = request.url.toString()
            requests += url
            when {
                url.endsWith("/api/front/v1/broadcasts/model") -> respond(
                    """{"item":{"status":"public"}}""",
                    headers = jsonHeaders
                )
                request.url.encodedPath.startsWith("/master/") && failPrimary && request.url.host == "127.0.0.1" ->
                    respond("not found", HttpStatusCode.NotFound)
                request.url.encodedPath.startsWith("/master/") -> respond(
                    """
                        #EXTM3U
                        #EXT-X-MOUFLON:PSCH:key-id
                        #EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=640x360,FRAME-RATE=30
                        http://127.0.0.1:18082/media/model.m3u8
                    """.trimIndent()
                )
                request.url.encodedPath == "/media/model.m3u8" -> respond("#EXTM3U")
                else -> error("unexpected request $url")
            }
        }) { expectSuccess = true }
        val provider = RecordingProvider(client, client)
        val eventBus = EventBus()
        installRequestAnswers(eventBus)
        val requestBus = RequestBus(eventBus, testScope)
        val dataChannel = DataChannel()
        val downloader = DownloaderComponent(dataChannel, eventBus = eventBus, parentScope = testScope)
        val session = SessionComponent(dataChannel, downloader, M3u8Parser, requestBus, eventBus, testScope)
        val candidateArgs = CopyOnWriteArrayList<Triple<Long, String, String?>>()
        val scheduler = SchedulerComponent(
            requestBus = requestBus,
            sessionComponent = session,
            apiClient = ApiClient(listOf("ignored.invalid"), provider) { "http://127.0.0.1:18082" },
            streamAuthKey = "fallback-key",
            eventBus = eventBus,
            parentScope = testScope,
            httpClientProvider = provider,
            runtimeTuning = RuntimeTuning(preconfigRetryInterval = 4.milliseconds),
            masterUrlCandidates = { roomId, pkey, token ->
                candidateArgs += Triple(roomId, pkey, token)
                buildList {
                    add("http://127.0.0.1:18082/master/$roomId?pkey=$pkey&token=${token.orEmpty()}")
                    if (failPrimary) add("http://127.0.0.2:18082/master/$roomId?pkey=$pkey&token=${token.orEmpty()}")
                }
            }
        )
        val entry = SchedulerEntry(7, "model", scheduler).apply { settings = RoomSettings(pkey = "room-key") }
        val oldCdnHosts = CdnSelector.hosts
        CdnSelector.updateHosts(emptyList())

        try {
            yield()
            val result = entry.preconfigSignal()

            assertEquals(SchedulerEvent.PreconfigDone, assertIs<SchedulerSignal>(result).event)
            assertEquals(listOf(Triple<Long, String, String?>(7L, "room-key", "")), candidateArgs)
            assertTrue(provider.proxiedCalls.any { it.key == "master_7" })
            assertTrue(provider.proxiedCalls.any { it.key == "preconfig_7" })
            assertTrue(requests.all {
                it.startsWith("http://127.0.0.1:18082/") || (failPrimary && it.startsWith("http://127.0.0.2:18082/master/"))
            })
            assertTrue(requests.any { it == "http://127.0.0.1:18082/master/7?pkey=room-key&token=" })
            assertTrue(requests.any { it == "http://127.0.0.1:18082/media/model.m3u8?psch=v2&pkey=key-id" })
            val stats = CdnSelector.snapshot()
            val successHost = if (failPrimary) "127.0.0.2" else "127.0.0.1"
            val success = stats.getValue(successHost)
            assertEquals(1, success.totalSuccesses)
            assertEquals(0, success.totalErrors)
            assertTrue(success.estimatedDurationMs > 0)
            assertTrue(success.estimateSource != "none")
            assertTrue(success.confidence > 0)
            if (failPrimary) {
                val failure = stats.getValue("127.0.0.1")
                assertEquals(0, failure.totalSuccesses)
                assertEquals(1, failure.totalErrors)
            }
        } finally {
            entry.scope.cancel()
            testScope.cancel()
            CdnSelector.updateHosts(oldCdnHosts)
            client.close()
        }
    }

    @Test
    fun `session uses injected playlist client poll interval and fetch timeout`() = runTest(UnconfinedTestDispatcher(), timeout = TEST_BUDGET) {
        val requests = CopyOnWriteArrayList<String>()
        val requestEvents = Channel<String>(Channel.UNLIMITED)
        val client = HttpClient(MockEngine { request ->
            requests += request.url.toString()
            requestEvents.trySend(request.url.toString())
            respond("#EXTM3U\n#EXT-X-MAP:URI=\"http://127.0.0.1:18083/init.mp4\"")
        })
        val provider = RecordingProvider(client, client)
        val eventBus = EventBus()
        installRequestAnswers(eventBus)
        val requestBus = RequestBus(eventBus, backgroundScope)
        val dataChannel = DataChannel()
        val downloader = DownloaderComponent(dataChannel, eventBus = eventBus, parentScope = backgroundScope)
        val session = SessionComponent(
            dataChannel,
            downloader,
            M3u8Parser,
            requestBus,
            eventBus,
            backgroundScope,
            httpClientProvider = provider,
            runtimeTuning = RuntimeTuning(
                playlistPollInterval = 6.milliseconds,
                playlistFetchTimeout = 50.milliseconds
            )
        )

        val entry = SessionEntry(9, "model", session).apply {
            playlistUrl = "http://127.0.0.1:18083/live.m3u8"
            pkey = "key-id"
            quality = "360p"
        }
        // No CDN candidates: this test pins the poll timing, and MockEngine answers on a real
        // dispatcher, so its fetches time out under virtual time. With candidates configured the
        // session would walk the playlist to another host after that failure (see the failover test
        // below) and the URL assertion would no longer hold.
        val oldCdnHosts = CdnSelector.hosts
        CdnSelector.updateHosts(emptyList())

        try {
            entry.fsm.drive(RecordingEvent.StartRecording)
            runCurrent()
            assertEquals("http://127.0.0.1:18083/live.m3u8", requestEvents.receive())
            assertEquals(1, requests.size)
            advanceTimeBy(5)
            runCurrent()
            assertEquals(1, requests.size)
            advanceTimeBy(1)
            runCurrent()
            requestEvents.receive()
            assertEquals(2, requests.size)
            assertTrue(provider.proxiedCalls.all { it.key == "m3u8_9" })
            assertTrue(requests.all { it == "http://127.0.0.1:18083/live.m3u8" })
        } finally {
            entry.scope.cancel()
            CdnSelector.updateHosts(oldCdnHosts)
            client.close()
        }
    }

    // Real time on purpose: the fetch is bounded by withTimeout, and MockEngine's dispatcher is not
    // the test scheduler, so virtual time would race ahead and time out a request that answered on
    // the next real millisecond (the same reason checkSchedulerMaster below uses runBlocking).
    @Test
    fun `session playlist fails over to the next CDN host when one stops answering`() = runBlocking {
        val testScope = CoroutineScope(coroutineContext + SupervisorJob())
        val requests = CopyOnWriteArrayList<String>()
        val client = HttpClient(MockEngine { request ->
            requests += request.url.toString()
            if (request.url.host == "127.0.0.1") throw IOException("connection reset by peer")
            respond("#EXTM3U\n#EXT-X-MAP:URI=\"http://127.0.0.2:18084/init.mp4\"")
        })
        val provider = RecordingProvider(client, client)
        val eventBus = EventBus()
        installRequestAnswers(eventBus)
        val requestBus = RequestBus(eventBus, testScope)
        val dataChannel = DataChannel()
        val downloader = DownloaderComponent(dataChannel, eventBus = eventBus, parentScope = testScope)
        val session = SessionComponent(
            dataChannel,
            downloader,
            M3u8Parser,
            requestBus,
            eventBus,
            testScope,
            httpClientProvider = provider
        )
        val entry = SessionEntry(9, "model", session).apply {
            playlistUrl = "http://127.0.0.1:18084/live.m3u8"
            pkey = "key-id"
            quality = "720p"
        }
        val oldCdnHosts = CdnSelector.hosts
        val errorsBefore = CdnSelector.snapshot()["127.0.0.1"]?.playlistErrors ?: 0
        CdnSelector.updateHosts(listOf("127.0.0.1", "127.0.0.2"))

        try {
            val failed = assertIs<SessionSignal>(entry.fetchPlaylistSignal())
            assertEquals(RecordingEvent.PlaylistFetchFailed, failed.event)
            // the dead host is remembered by this session, and the playlist walked to the other one
            assertEquals(listOf("127.0.0.1"), entry.playlistHostsTried.toList())
            assertEquals("http://127.0.0.2:18084/live.m3u8", entry.playlistUrl)
            // the pooled client is dropped, so the retry cannot be handed the connection that failed
            assertEquals(listOf("m3u8_9"), provider.evicted)
            // and the failure reaches the selector as a *playlist* failure, so the next preconfig
            // avoids the host too
            assertEquals(errorsBefore + 1, CdnSelector.snapshot().getValue("127.0.0.1").playlistErrors)

            val fetched = assertIs<SessionSignal>(entry.fetchPlaylistSignal())
            assertEquals(RecordingEvent.PlaylistFetched, fetched.event, "failReason=${fetched.data?.failReason}")
            assertTrue(requests.any { it == "http://127.0.0.2:18084/live.m3u8" })
            // a served playlist clears the playlist penalty of the host that served it, and only
            // that host's: the failed one keeps its streak for the next preconfig
            assertEquals(0, CdnSelector.snapshot()["127.0.0.2"]?.playlistFailures ?: 0)
            assertEquals(1, CdnSelector.snapshot().getValue("127.0.0.1").playlistFailures)
        } finally {
            entry.scope.cancel()
            testScope.cancel()
            CdnSelector.updateHosts(oldCdnHosts)
            client.close()
        }
    }

    // Real time on purpose, for the same reason as the failover test above. The race delay, the
    // attempt window and the stall watchdog are wall-clock durations, while MockEngine answers on
    // its own dispatcher: under the virtual clock the guard below could burn its whole timeout
    // before the download had been handed a single response, and the failure said only "no
    // StreamData" without showing the download was still perfectly healthy (observed once:
    // direct=13, proxy=12, every proxy answering, nothing yet delivered).
    @Test
    fun `downloader uses injected direct proxy and probe clients with short race timing`() = runBlocking {
        val directRequests = CopyOnWriteArrayList<Pair<HttpMethod, String>>()
        val proxyRequests = CopyOnWriteArrayList<String>()
        // The direct path is held open by a latch rather than a 20ms sleep: the race must be decided
        // by "direct is still busy", not by whether a real delay outran the race window, which
        // flipped under CPU load.
        //
        // The latch is bounded. Unbounded, it is a trap: under load the direct path can be the one
        // that completes first, and then this handler parks on the gate forever — the test then
        // waits on `dataChannel.receive()` for its whole budget and fails with "no StreamData",
        // which says nothing about what happened. Releasing after a generous pause keeps the race
        // honest (the direct attempt is still busy well past the race window) and lets a wrong
        // outcome surface as itself.
        val directGate = CompletableDeferred<Unit>()
        val direct = HttpClient(MockEngine { request ->
            directRequests += request.method to request.url.toString()
            if (request.method == HttpMethod.Get) {
                withTimeoutOrNull(DIRECT_GATE_HOLD) { directGate.await() }
            }
            respond("direct")
        })
        val proxied = HttpClient(MockEngine { request ->
            proxyRequests += request.url.toString()
            respond("proxy")
        })
        val provider = RecordingProvider(direct, proxied)
        val eventBus = EventBus()
        val dataChannel = DataChannel()
        val oldCdnHosts = CdnSelector.hosts
        CdnSelector.updateHosts(listOf("127.0.0.1", "127.0.0.2"))
        val testScope = CoroutineScope(coroutineContext + SupervisorJob())
        val downloader = DownloaderComponent(
            dataChannel,
            eventBus = eventBus,
            parentScope = testScope,
            httpClientProvider = provider,
            // The windows are deliberately wider than the behaviour needs. The race is decided
            // by the gate above ("direct is still busy"), not by these numbers, so tightening
            // them only buys flakiness: at 2ms/100ms/200ms a MockEngine request that is merely
            // CPU-starved outran the whole segment deadline and the download produced nothing
            // (observed: attempts=16, direct=16, proxy=15). Wide enough to be reliable, still
            // far too narrow for the direct path to finish and win.
            runtimeTuning = RuntimeTuning(
                downloaderRaceDelay = 50.milliseconds,
                downloaderAttemptTimeout = 2.seconds,
                downloaderDeadline = 5.seconds,
                downloaderStallTimeout = 1.seconds,
                downloaderRetryBackoff = 10.milliseconds
            )
        )

        try {
            downloader.start()
            downloader.tell(DoDownload(Download(11, listOf(Segment("http://127.0.0.1/segment.mp4", 1)), 0, 99)))
            // Bounded: a segment that never arrives should fail here, naming what was awaited,
            // rather than let the whole test hit its budget with no explanation.
            val stream = withTimeoutOrNull(DIRECT_GATE_HOLD) { dataChannel.receive() } ?: error(
                "no StreamData within $DIRECT_GATE_HOLD: direct=${directRequests.size}, proxy=${proxyRequests.size}, " +
                    "directKeys=${provider.directCalls.map { it.key }.distinct()}, proxyKeys=${provider.proxiedCalls.map { it.key }.distinct()}, " +
                    "proxyUrls=${proxyRequests.take(3)}"
            )

            assertIs<StreamData>(stream)
            assertEquals("proxy", stream.data.decodeToString())
            assertTrue(provider.directCalls.any { it.key.startsWith("dl_") })
            assertTrue(provider.directCalls.any { it.key.startsWith("probe_") })
            assertTrue(provider.proxiedCalls.any { it.key.startsWith("px_") })
            assertTrue((directRequests.map { it.second } + proxyRequests).all { it.startsWith("http://127.0.0.") })
        } finally {
            // Release the direct path before tearing down: the raced loser is normally cancelled,
            // but a MockEngine handler runs in the client's own scope and can outlive that cancel —
            // leaving it parked on the gate keeps the direct client from ever closing.
            directGate.complete(Unit)
            downloader.stop()
            testScope.cancel()
            CdnSelector.updateHosts(oldCdnHosts)
            direct.close()
            proxied.close()
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

    private data class ClientCall(
        val key: String,
        val http1: Boolean,
        val expectSuccess: Boolean
    )

    private class RecordingProvider(
        private val directClient: HttpClient,
        private val proxiedClient: HttpClient
    ) : HttpClientProvider {
        val directCalls = CopyOnWriteArrayList<ClientCall>()
        val proxiedCalls = CopyOnWriteArrayList<ClientCall>()
        val evicted = CopyOnWriteArrayList<String>()

        override fun direct(key: String, http1: Boolean, expectSuccess: Boolean): HttpClient {
            directCalls += ClientCall(key, http1, expectSuccess)
            return directClient
        }

        override fun proxied(key: String, http1: Boolean, expectSuccess: Boolean): HttpClient {
            proxiedCalls += ClientCall(key, http1, expectSuccess)
            return proxiedClient
        }

        override fun evict(key: String) {
            evicted += key
        }
    }

    private companion object {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    }
}

/** How long the direct path stays "busy" while the proxy is meant to win the race. */
private val DIRECT_GATE_HOLD = 30.seconds
