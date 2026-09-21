package github.rikacelery.v3.components

import github.rikacelery.v3.api.ApiClient
import github.rikacelery.v3.core.DataChannel
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.data.RoomSettings
import github.rikacelery.v3.data.RuntimeTuning
import github.rikacelery.v3.data.User
import github.rikacelery.v3.events.CommandAck
import github.rikacelery.v3.events.CommandEnvelope
import github.rikacelery.v3.events.ConfigResponse
import github.rikacelery.v3.events.DecryptKeyMatch
import github.rikacelery.v3.events.GetDecryptKey
import github.rikacelery.v3.events.GetRoomConfig
import github.rikacelery.v3.events.GetValidPaymentAccount
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
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #192, the half that made a restart necessary: a private show whose account has no free-spy
 * privilege marks the room exhausted, and the marker used to stand until the room was re-armed by
 * hand — even after the operator entered the show in a browser and the privilege applied. The
 * verdict is read from a live fan-club payload, so the room re-probes it slowly and picks the show
 * up on its own.
 */
class SchedulerFreeSpyReprobeTest {

    @Test
    fun `a room with no free spy privilege picks the show up when the verdict changes`() = runBlocking {
        val granted = AtomicInteger(0)   // 0 = no benefit, 1 = the account may spy for free
        withPrivateRoom(granted) { h ->
            assertTrue(
                awaitUntil(10.seconds) { h.entry.freeSpyExhausted },
                "the room must stop probing once the privilege is known to be missing " +
                        "(state=${h.entry.fsm.currentState}, verdicts=${h.entry.freeSpyVerdicts})"
            )
            assertEquals("4242=benefit-inactive", h.entry.freeSpyVerdicts, "the verdict says why it is not recording")
            assertEquals(SchedulerState.Armed, h.entry.fsm.currentState)

            // the operator enters the show in a browser (or the benefit activates): nothing tells the
            // recorder about it, exactly as in the report
            granted.set(1)

            assertTrue(
                awaitUntil(10.seconds) { h.entry.fsm.currentState == SchedulerState.Recording },
                "the room must re-probe the privilege on its own instead of waiting for a restart " +
                        "(state=${h.entry.fsm.currentState}, verdicts=${h.entry.freeSpyVerdicts})"
            )
            assertFalse(h.entry.freeSpyExhausted)
            assertEquals("4242=granted", h.entry.freeSpyVerdicts)
        }
    }

    @Test
    fun `a room whose show ended is not driven back onto the private route by the pending re-probe`() = runBlocking {
        val granted = AtomicInteger(0)
        withPrivateRoom(granted) { h ->
            assertTrue(awaitUntil(10.seconds) { h.entry.freeSpyExhausted }, "the marker has to be set first")

            // the show ends for real: the platform's live channel says so, and the read agrees
            h.readStatus.set("off")
            h.eventBus.publish(RoomStatusChanged(7, "private", "off"))
            assertTrue(
                awaitUntil(5.seconds) { !h.entry.freeSpyExhausted },
                "leaving the private status must lift the marker and its pending re-probe"
            )
            val attemptsSoFar = h.masterRequests()

            delay(700.milliseconds)   // well past the re-probe interval this test runs with

            assertEquals(
                SchedulerState.Armed,
                h.entry.fsm.currentState,
                "a show that ended must not be preconfigured again by a stale timer"
            )
            assertEquals(
                attemptsSoFar,
                h.masterRequests(),
                "the stale re-probe must not start another preconfig attempt"
            )
        }
    }

    // —— harness ——

    private class Harness(
        val scheduler: SchedulerComponent,
        val eventBus: EventBus,
        val entry: SchedulerEntry,
        /** The status the platform's own read answers with; the test moves it to end a show. */
        val readStatus: AtomicReference<String>,
        /** Master-playlist requests, i.e. one per preconfig attempt that got a token. */
        val masterRequests: () -> Int
    )

    /**
     * Drives one armed room on a private show for an account whose free-spy benefit is whatever
     * [granted] currently says, with the re-probe interval shortened to fit a test.
     */
    private suspend fun withPrivateRoom(granted: AtomicInteger, block: suspend (Harness) -> Unit) {
        val readStatus = AtomicReference("private")
        val masters = AtomicInteger()
        val cdn = HttpClient(MockEngine { request ->
            val url = request.url.toString()
            when {
                url.endsWith("/api/front/v1/broadcasts/model") ->
                    respond("""{"item":{"status":"${readStatus.get()}"}}""", headers = jsonHeaders)

                url.contains("/api/front/v2/models/7/cam") ->
                    respond(camPayload(granted.get() == 1), headers = jsonHeaders)

                request.url.encodedPath.startsWith("/master/") -> {
                    masters.incrementAndGet()
                    respond(masterPlaylist)
                }

                request.url.encodedPath == "/media/model.m3u8" -> respond("#EXTM3U")

                else -> error("unexpected request $url")
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
                        is GetValidPaymentAccount -> listOf(User("cookie", 4242, "tester", 1_000))
                        is MatchDecryptKeys -> DecryptKeyMatch("key-id", "decrypt-key")
                        is GetDecryptKey -> ConfigResponse("decrypt-key")
                        is RefreshRoomCmd -> OkResponse
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
            runtimeTuning = RuntimeTuning(
                // long enough that only the re-probe can start the second attempt
                preconfigRetryInterval = 30.seconds,
                preconfigProbeTimeout = 3.seconds,
                freeSpyReprobeInterval = 300.milliseconds
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
                SchedulerSignal(7, SchedulerEvent.RoomStatusChanged, SchedulerDriveData(roomStatus = "private"))
            )
            block(Harness(scheduler, eventBus, entry, readStatus) { masters.get() })
        } finally {
            scheduler.stop()
            CdnSelector.updateHosts(oldCdnHosts)
            scope.cancel()
            cdn.close()
        }
    }

    private suspend fun awaitUntil(
        timeout: kotlin.time.Duration = 5.seconds,
        predicate: () -> Boolean
    ): Boolean = withTimeoutOrNull(timeout) {
        while (!predicate()) delay(20.milliseconds)
        true
    } ?: false

    private companion object {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

        val masterPlaylist = """
            #EXTM3U
            #EXT-X-MOUFLON:PSCH:key-id
            #EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=640x360,FRAME-RATE=30
            http://127.0.0.1:18082/media/model.m3u8
        """.trimIndent()

        /** The cam payload of a fan-club account, with the free-spying benefit active or not. */
        fun camPayload(freeSpy: Boolean) = buildJsonObject {
            put("cam", buildJsonObject {
                put("modelToken", "spy-token")
                put("userFanClub", buildJsonObject {
                    put("subscription", buildJsonObject {
                        put("status", "active")
                        put("tier", "tier1")
                    })
                    put("benefits", buildJsonArray {
                        add(buildJsonObject {
                            put("id", "freeSpying")
                            put("tiers", buildJsonObject {
                                put("tier1", buildJsonObject { put("isActive", freeSpy) })
                            })
                        })
                    })
                })
            })
        }.toString()
    }
}
