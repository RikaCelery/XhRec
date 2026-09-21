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
import github.rikacelery.v3.hooks.EventHook
import github.rikacelery.v3.m3u8.M3u8Parser
import github.rikacelery.v3.utils.CdnSelector
import github.rikacelery.v3.utils.HttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.coroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #192: a private show was probed with the *public* URL, the CDN answered 403, and the room
 * looked like one whose show had moved on — it retried the same thing every interval until it was
 * re-armed by hand.
 *
 * The status reaches the entry from two places: the platform's live channel (the status the room is
 * driven with) and the preconfig's own REST read, which can still be the cached answer from before
 * the show switched. The room's own status wins that disagreement now, so a stale "public" read no
 * longer sends a private room down the token-less path — and when there is no spy route to a token
 * at all, the attempt fails with the reason instead of probing the CDN.
 */
class SchedulerStaleStatusReadTest {

    @Test
    fun `a stale public read never turns a private room into a token-less probe`() = runBlocking {
        withScenario(readStatus = "public", roomStatus = "private", freeSpy = true) { h ->
            assertTrue(
                awaitUntil(10.seconds) { h.entry.fsm.currentState == SchedulerState.Recording },
                "the room must resolve the private show from its own status, not from the stale read " +
                        "(state=${h.entry.fsm.currentState}, statusRead=${h.entry.statusRead}, " +
                        "tokenSource=${h.entry.tokenSource}, reason=${h.entry.lastFailReason})"
            )

            assertEquals("private", h.entry.roomStatus, "the room's own status wins the disagreement")
            assertEquals("public", h.entry.statusRead, "the read is still reported for the dashboard")
            assertEquals("freeSpy", h.entry.tokenSource, "the private route is the one that must be taken")
            assertEquals("spy-token".length, h.entry.tokenLength)
            assertTrue(
                h.mediaRequests.isNotEmpty() && h.mediaRequests.all { Url(it).parameters["aclAuth"] == "spy-token" },
                "a private show must never be probed without a token: ${h.mediaRequests}"
            )
        }
    }

    @Test
    fun `a private room with no spy route fails with the reason instead of probing the CDN`() = runBlocking {
        withScenario(readStatus = "public", roomStatus = "private", freeSpy = false) { h ->
            assertTrue(
                awaitUntil(10.seconds) { h.entry.lastFailReason != null },
                "the attempt must fail with a reason (state=${h.entry.fsm.currentState})"
            )

            assertEquals("private", h.entry.roomStatus)
            assertEquals("public", h.entry.statusRead)
            assertEquals("none", h.entry.tokenSource)
            assertTrue(
                h.mediaRequests.isEmpty(),
                "nothing may be probed without a token: ${h.mediaRequests}"
            )
        }
    }

    /**
     * The other direction must keep working: the room is still driven as public, the platform has
     * already switched it to private (its push has not landed yet), and the fresh read is the one
     * that knows. That read is followed, so the show is resolved as private from the start.
     */
    @Test
    fun `a fresh private read still switches a public room onto the private route`() = runBlocking {
        withScenario(readStatus = "private", roomStatus = "public", freeSpy = true) { h ->
            assertTrue(
                awaitUntil(10.seconds) { h.entry.fsm.currentState == SchedulerState.Recording },
                "a room that reads as private must be resolved as private " +
                        "(state=${h.entry.fsm.currentState}, tokenSource=${h.entry.tokenSource}, " +
                        "reason=${h.entry.lastFailReason})"
            )

            assertEquals("private", h.entry.roomStatus, "the fresh read is followed when it is the stricter one")
            assertEquals("private", h.entry.statusRead)
            assertEquals("freeSpy", h.entry.tokenSource)
        }
    }

    // —— harness ——

    private class Harness(
        val entry: SchedulerEntry,
        /** Every variant-playlist request the run made, in order, with its query string. */
        val mediaRequests: List<String>
    )

    /**
     * Drives one armed room whose preconfig REST read always answers [readStatus] while the room is
     * told [roomStatus] over the live channel. The CDN answers the variant playlist only to a
     * request that carries `aclAuth`, which is what a real private show does and what makes a
     * token-less probe visible here.
     */
    private suspend fun withScenario(
        readStatus: String,
        roomStatus: String,
        freeSpy: Boolean,
        block: suspend (Harness) -> Unit
    ) {
        val mediaRequests = CopyOnWriteArrayList<String>()
        val cdn = HttpClient(MockEngine { request ->
            val url = request.url.toString()
            when {
                url.endsWith("/api/front/v1/broadcasts/model") ->
                    respond("""{"item":{"status":"$readStatus"}}""", headers = jsonHeaders)

                url.contains("/api/front/v2/models/7/cam") ->
                    respond(camPayload(freeSpy), headers = jsonHeaders)

                request.url.encodedPath.startsWith("/master/") -> respond(masterPlaylist)

                request.url.encodedPath == "/media/model.m3u8" -> {
                    mediaRequests += url
                    if (request.url.parameters["aclAuth"] == "spy-token") respond("#EXTM3U")
                    else respond("rejected", HttpStatusCode.Forbidden)
                }

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
                SchedulerSignal(7, SchedulerEvent.RoomStatusChanged, SchedulerDriveData(roomStatus = roomStatus))
            )
            block(Harness(entry, mediaRequests))
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
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

        val masterPlaylist = """
            #EXTM3U
            #EXT-X-MOUFLON:PSCH:key-id
            #EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=640x360,FRAME-RATE=30
            http://127.0.0.1:18082/media/model.m3u8
        """.trimIndent()

        /** The cam payload a fan-club account with the free-spy benefit sees on a private show. */
        fun camPayload(freeSpy: Boolean) = buildJsonObject {
            put("cam", buildJsonObject {
                put("modelToken", "spy-token")
                put("userFanClub", buildJsonObject {
                    put("subscription", buildJsonObject {
                        put("status", if (freeSpy) "active" else "none")
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
