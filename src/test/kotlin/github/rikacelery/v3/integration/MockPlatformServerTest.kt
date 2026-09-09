package github.rikacelery.v3.integration

import github.rikacelery.v3.m3u8.M3u8Parser
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Drives the single loopback mock server directly: platform JSON, playlists, byte-exact
 * media, request history, faults, and the WebSocket subscribe/push contract.
 */
class MockPlatformServerTest {

    @Test
    fun `binds an ephemeral loopback port`() = withMock { mock ->
        assertTrue(mock.port > 0, "port=${mock.port}")
        assertTrue(mock.baseUrl.startsWith("http://127.0.0.1:"), mock.baseUrl)
        assertTrue(mock.wsUrl.startsWith("ws://127.0.0.1:"), mock.wsUrl)
    }

    @Test
    fun `serves platform config broadcast info and cam info`() = withMock { mock ->
        val room = mock.addRoom(7, "model", status = "public")
        withClient { client ->
            val initial = client.get("${mock.baseUrl}/api/front/v3/config/initial").bodyAsText()
            assertEquals(mock.token, initial.jsonPath("initial.client.websocket.token"))
            assertEquals("tester", initial.jsonPath("initial.client.user.username"))

            val broadcast = client.get("${mock.baseUrl}/api/front/v1/broadcasts/model").bodyAsText()
            assertEquals("public", broadcast.jsonPath("item.status"))
            assertEquals(7L, broadcast.jsonPath("item.modelId").toLong())

            mock.setRoomStatus(room.id, "p2p")
            assertEquals(
                "p2p",
                client.get("${mock.baseUrl}/api/front/v1/broadcasts/model").bodyAsText().jsonPath("item.status")
            )

            val missing = client.get("${mock.baseUrl}/api/front/v1/broadcasts/ghost")
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertTrue(missing.bodyAsText().contains("description"))

            val cam = client.get("${mock.baseUrl}/api/front/v2/models/7/cam").bodyAsText()
            assertEquals("", cam.jsonPath("cam.modelToken"))
            assertEquals("100", cam.jsonPath("user.user.ticketRate"))
        }
    }

    @Test
    fun `serves master and media playlists that decrypt to exact segment bytes`() = withMock { mock ->
        val room = mock.addRoom(7, "model", status = "public")
        room.availableSegments = 2
        withClient { client ->
            val master = M3u8Parser.parseMaster(client.get(mock.masterUrl(7)).bodyAsText())
            assertEquals(listOf("key-id"), master.pschKeys.map { it.substringAfter(":") })

            assertEquals(listOf("360p", "720p"), master.variants.map { it.name })
            val mediaUrl = master.variants.maxByOrNull { it.bandwidth }!!.url
            assertEquals(mock.mediaUrl(7), mediaUrl)
            val parsed = M3u8Parser.parse(client.get(mediaUrl).bodyAsText(), mock.decryptKey)

            assertEquals(MockPayloads.initUrl(mock.baseUrl, 7, room.generation), parsed.initUrl)
            assertEquals(2, parsed.segments.size)

            val assembled = java.io.ByteArrayOutputStream()
            val init = client.get(parsed.initUrl!!).bodyAsBytes()
            assembled.write(init)
            assertContentEquals(MockPayloads.initBytes(7, room.generation), init)
            parsed.segments.forEachIndexed { offset, segment ->
                val index = offset + 1
                assertEquals(MockPayloads.segmentUrl(mock.baseUrl, 7, room.generation, index), segment.url)
                val bytes = client.get(segment.url).bodyAsBytes()
                assertEquals(MockPayloads.SEGMENT_SIZE, bytes.size)
                assembled.write(bytes)
                assertContentEquals(MockPayloads.segmentBytes(7, room.generation, index), bytes)
            }
            assertContentEquals(mock.expectedBytes(7, room.generation, throughIndex = 2), assembled.toByteArray())
        }
    }

    @Test
    fun `records loopback request history`() = withMock { mock ->
        mock.addRoom(7, "model", status = "public")
        withClient { client ->
            client.get(mock.masterUrl(7))
            client.get("${mock.baseUrl}/api/front/v1/broadcasts/model?probe=1")
        }
        val paths = mock.requests().map { it.path }
        assertTrue(paths.contains("/hls/7/master/7_auto.m3u8"), "$paths")
        assertTrue(paths.contains("/api/front/v1/broadcasts/model"), "$paths")
        assertTrue(mock.requests().all { it.path.startsWith("/") })
        assertEquals(
            mapOf("probe" to "1"),
            mock.requests().first { it.path == "/api/front/v1/broadcasts/model" }.query
        )
    }

    @Test
    fun `applies next-request faults`() = withMock { mock ->
        mock.addRoom(7, "model", status = "public")
        withClient { client ->
            val media = mock.mediaUrl(7)

            mock.failNext(mock.mediaPath(7), MockFault.NotFound)
            assertEquals(HttpStatusCode.NotFound, client.get(media).status)
            assertEquals(HttpStatusCode.OK, client.get(media).status)

            mock.failNext(mock.mediaPath(7), MockFault.ServerError)
            assertEquals(HttpStatusCode.InternalServerError, client.get(media).status)
            assertEquals(HttpStatusCode.OK, client.get(media).status)

            mock.failNext(mock.mediaPath(7), MockFault.Delay(150.milliseconds))
            val startedAt = System.currentTimeMillis()
            assertEquals(HttpStatusCode.OK, client.get(media).status)
            assertTrue(System.currentTimeMillis() - startedAt >= 140, "delay was not applied")

            mock.failNext(mock.mediaPath(7), MockFault.Interrupt)
            val interrupted = runCatching { client.get(media).bodyAsText() }
            assertTrue(interrupted.isFailure, "interruption must surface as a transport failure")
        }
    }

    @Test
    fun `advances segments and rotates statuses automatically`() = withMock { mock ->
        val room = mock.addRoom(7, "model", status = "off")
        val segments = mock.startSegments(7, 40.milliseconds)
        val rotation = mock.rotateStatuses(7, listOf("off", "public", "groupShow"), 40.milliseconds)
        try {
            withTimeout(5.seconds) {
                while (room.availableSegments < 3 || room.status == "off") delay(20.milliseconds)
            }
            assertTrue(room.availableSegments >= 3, "available=${room.availableSegments}")
            assertTrue(room.status != "off", "status=${room.status}")
            assertTrue(mock.pushes().any { it.channel.startsWith("broadcastChanged@7") })
        } finally {
            segments.cancelAndJoin()
            rotation.cancelAndJoin()
        }
    }

    @Test
    fun `delivers pushes only to subscribed authenticated websockets`() = withMock { mock ->
        val room = mock.addRoom(7, "model", status = "off")
        withClient { client ->
            client.webSocket(mock.wsUrl) {
                val subscriber = this
                send(Frame.Text("""{"connect":{"token":"${mock.token}","name":"js"},"id":1}"""))
                send(Frame.Text("""{"subscribe":{"channel":"broadcastChanged@7"},"id":2}"""))
                assertTrue(mock.awaitSubscription("broadcastChanged@7", 2.seconds))

                client.webSocket(mock.wsUrl) {
                    val observer = this
                    send(Frame.Text("""{"connect":{"token":"${mock.token}","name":"js"},"id":1}"""))
                    mock.setRoomStatus(room.id, "public", push = true)

                    val text = assertNotNull(subscriber.awaitPush(3.seconds), "subscriber must receive the push")
                    assertEquals("broadcastChanged@7", text.jsonPath("push.channel"))
                    assertEquals("public", text.jsonPath("push.pub.data.status"))

                    // the unsubscribed sibling connection must not receive the push
                    assertTrue(observer.awaitPush(300.milliseconds) == null)
                }
            }
        }
    }

    @Test
    fun `rejects websockets then restores them`() = withMock { mock ->
        mock.addRoom(7, "model", status = "public")
        withClient { client ->
            mock.rejectWebSockets(true)
            val rejected = runCatching {
                client.webSocket(mock.wsUrl) {
                    send(Frame.Text("""{"connect":{"token":"${mock.token}"},"id":1}"""))
                    withTimeoutOrNull(300.milliseconds) { incoming.receive() }
                }
            }
            assertTrue(rejected.isFailure || rejected.getOrNull() == null, "connection must not stay usable")

            mock.rejectWebSockets(false)
            client.webSocket(mock.wsUrl) {
                send(Frame.Text("""{"connect":{"token":"${mock.token}"},"id":1}"""))
                send(Frame.Text("""{"subscribe":{"channel":"broadcastChanged@7"},"id":2}"""))
                assertTrue(mock.awaitSubscription("broadcastChanged@7", 2.seconds))
            }
        }
    }

    @Test
    fun `disconnects websockets on demand`() = withMock { mock ->
        mock.addRoom(7, "model", status = "public")
        withClient { client ->
            client.webSocket(mock.wsUrl) {
                send(Frame.Text("""{"connect":{"token":"${mock.token}"},"id":1}"""))
                send(Frame.Text("""{"subscribe":{"channel":"broadcastChanged@7"},"id":2}"""))
                assertTrue(mock.awaitSubscription("broadcastChanged@7", 2.seconds))
                assertEquals(1, mock.connectionCount())

                mock.disconnectWebSockets()
                withTimeout(3.seconds) {
                    while (mock.connectionCount() != 0) delay(20.milliseconds)
                }

                // a closed session must no longer receive pushes
                mock.setRoomStatus(7, "public", push = true)
                assertTrue(mock.pushes().isNotEmpty())
                assertTrue(runCatching { awaitPush(300.milliseconds) }.getOrNull() == null)
            }
            assertEquals(0, mock.connectionCount())
        }
    }

    @Test
    fun `records segment completion order when a fetch is delayed`() = withMock { mock ->
        val room = mock.addRoom(7, "model", status = "public")
        room.availableSegments = 2
        mock.delaySegment(7, 1, 300.milliseconds)
        withClient { client ->
            coroutineScope {
                val first = async { client.get(MockPayloads.segmentUrl(mock.baseUrl, 7, room.generation, 1)).bodyAsBytes() }
                delay(30.milliseconds)
                val second = async { client.get(MockPayloads.segmentUrl(mock.baseUrl, 7, room.generation, 2)).bodyAsBytes() }
                assertContentEquals(MockPayloads.segmentBytes(7, room.generation, 2), second.await())
                assertContentEquals(MockPayloads.segmentBytes(7, room.generation, 1), first.await())
            }
        }
        assertEquals(listOf(2, 1), mock.completionOrder(7))
    }

    private fun withMock(block: suspend (MockPlatformServer) -> Unit) = kotlinx.coroutines.runBlocking {
        MockPlatformServer().use { mock -> block(mock) }
    }

    private suspend fun withClient(block: suspend (HttpClient) -> Unit) {
        HttpClient(OkHttp) { install(WebSockets) }.use { client -> block(client) }
    }
}

/** Reads frames until a push arrives, ignoring connect/subscribe acknowledgements. */
private suspend fun DefaultClientWebSocketSession.awaitPush(timeout: kotlin.time.Duration): String? =
    withTimeoutOrNull(timeout) {
        var result: String? = null
        while (result == null) {
            val frame = incoming.receive()
            if (frame is Frame.Text) {
                val text = frame.readText()
                if ("\"push\"" in text) result = text
            }
        }
        result
    }

private fun String.jsonPath(path: String): String {
    var element: kotlinx.serialization.json.JsonElement = kotlinx.serialization.json.Json.parseToJsonElement(this)
    for (part in path.split('.')) {
        element = element.jsonObject[part] ?: error("missing '$part' in $this")
    }
    return element.jsonPrimitive.content
}
