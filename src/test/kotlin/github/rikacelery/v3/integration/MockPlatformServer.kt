package github.rikacelery.v3.integration

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.writeFully
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A request the mock platform served, in arrival order. */
data class MockRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val atMs: Long
)

/** A WebSocket push the mock platform emitted. */
data class MockPush(val channel: String, val data: JsonObject)

/** Fault injected for the next matching request. */
sealed interface MockFault {
    data object NotFound : MockFault
    data object ServerError : MockFault
    data class Delay(val duration: Duration) : MockFault
    data object Interrupt : MockFault
}

/** Mutable per-room state owned by the mock platform. */
class MockRoom(
    val id: Long,
    val name: String,
    status: String,
    streamStatus: String
) {
    @Volatile var status: String = status
    @Volatile var streamStatus: String = streamStatus
    @Volatile var generation: Int = 1
    @Volatile var availableSegments: Int = 0
    @Volatile var modelToken: String = ""
    @Volatile var freeSpyAccess: Boolean = false
    @Volatile var ticketRate: Int = 100
    @Volatile var privateRate: Int = 50
    val presets: MutableList<String> = CopyOnWriteArrayList(listOf("360p", "720p"))
    @Volatile var fps: Int = 30
    @Volatile var height: Int = 720

    /** Segment indexes in the order the mock finished writing their bodies. */
    val completionOrder: MutableList<Int> = CopyOnWriteArrayList()

    fun advanceSegment(): Int = ++availableSegments
}

/**
 * One loopback Ktor server that impersonates the XhRec platform: platform JSON APIs,
 * master/media playlists, byte-exact init/segment media, and the WebSocket
 * connect/subscribe/push protocol. Nothing here touches the public internet.
 */
class MockPlatformServer(
    val decryptKey: String = MockPayloads.DEFAULT_DECRYPT_KEY,
    val pschKeyName: String = PSCH_KEY,
    val windowSize: Int = MockPayloads.DEFAULT_WINDOW,
    val token: String = "mock-ws-token"
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rooms = ConcurrentHashMap<Long, MockRoom>()
    private val roomsByName = ConcurrentHashMap<String, MockRoom>()
    private val recordedRequests = CopyOnWriteArrayList<MockRequest>()
    private val recordedPushes = CopyOnWriteArrayList<MockPush>()
    private val sessions = CopyOnWriteArrayList<WsHandle>()
    private val faults = ConcurrentHashMap<String, ConcurrentLinkedQueue<MockFault>>()
    private val segmentDelays = ConcurrentHashMap<String, Duration>()
    private val rejectWs = AtomicBoolean(false)
    private val ownedJobs = CopyOnWriteArrayList<Job>()
    private val connectionIds = AtomicInteger(0)

    private val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { module() }

    val port: Int
    val baseUrl: String
    val wsUrl: String

    /** Host:port form used as the configured platform/WS host inside tests. */
    val host: String get() = "127.0.0.1:$port"

    init {
        server.start(wait = false)
        port = runBlocking { server.engine.resolvedConnectors().first().port }
        baseUrl = "http://127.0.0.1:$port"
        wsUrl = "ws://127.0.0.1:$port/connection/websocket"
    }

    // ── state control ──────────────────────────────────────────────────────────

    fun addRoom(id: Long, name: String, status: String = "off", streamStatus: String = "distributing"): MockRoom {
        val room = MockRoom(id, name, status, streamStatus)
        rooms[id] = room
        roomsByName[name] = room
        return room
    }

    fun room(id: Long): MockRoom = rooms[id] ?: error("no mock room $id")

    fun masterUrl(roomId: Long): String = baseUrl + masterPath(roomId)

    fun masterPath(roomId: Long): String = "/hls/$roomId/master/${roomId}_auto.m3u8"

    fun mediaUrl(roomId: Long, quality: String = "highest"): String = baseUrl + mediaPath(roomId, quality)

    /** "highest" resolves to the top variant in the ladder (720p), matching the Scheduler's default. */
    fun mediaPath(roomId: Long, quality: String = "highest"): String {
        val variant = if (quality == "highest") "720p" else quality
        return "/hls/$roomId/media/${roomId}_$variant.m3u8"
    }

    private fun masterPlaylist(roomId: Long): String = MockPayloads.masterPlaylistText(
        listOf(
            MockPayloads.MasterVariant(800_000, "640x360", 30, mediaUrl(roomId, "360p")),
            MockPayloads.MasterVariant(2_000_000, "1280x720", 30, mediaUrl(roomId, "720p"))
        ),
        pschKeyName
    )

    suspend fun setRoomStatus(id: Long, status: String, push: Boolean = true) {
        val room = room(id)
        room.status = status
        if (push) {
            push(
                "broadcastChanged@$id",
                buildJsonObject { put("status", status) }
            )
        }
    }

    suspend fun setStreamStatus(id: Long, status: String, push: Boolean = true) {
        val room = room(id)
        room.streamStatus = status
        if (push) {
            push(
                "streamChanged@$id",
                buildJsonObject { put("status", status) }
            )
        }
    }

    /** Publishes one more segment every [period] until the returned job is cancelled. */
    fun startSegments(id: Long, period: Duration): Job = scope.launch {
        while (true) {
            delay(period)
            room(id).advanceSegment()
        }
    }.also { ownedJobs += it }

    /** Applies [statuses] in order (first immediately), then loops every [period]. */
    fun rotateStatuses(id: Long, statuses: List<String>, period: Duration): Job {
        require(statuses.isNotEmpty())
        return scope.launch {
            var index = 0
            while (true) {
                setRoomStatus(id, statuses[index % statuses.size])
                index++
                delay(period)
            }
        }.also { ownedJobs += it }
    }

    /** Bumps the generation so the next playlist advertises a new init segment. */
    fun rotateInit(id: Long): Int {
        val room = room(id)
        room.generation += 1
        return room.generation
    }

    suspend fun disconnectWebSockets() {
        sessions.toList().forEach { it.close() }
    }

    fun rejectWebSockets(value: Boolean) {
        rejectWs.set(value)
    }

    fun failNext(path: String, fault: MockFault) {
        faults.computeIfAbsent(path) { ConcurrentLinkedQueue() }.add(fault)
    }

    fun delaySegment(roomId: Long, index: Int, delay: Duration) {
        segmentDelays["$roomId:$index"] = delay
    }

    // ── observations ───────────────────────────────────────────────────────────

    fun requests(): List<MockRequest> = recordedRequests.toList()

    fun pushes(): List<MockPush> = recordedPushes.toList()

    fun completionOrder(roomId: Long): List<Int> = room(roomId).completionOrder.toList()

    fun connectionCount(): Int = sessions.size

    fun subscribedChannels(): Set<String> = sessions.flatMap { it.subscribed.toList() }.toSet()

    /** Blocks until any session subscribes to [channel]. */
    suspend fun awaitSubscription(channel: String, timeout: Duration = 2.seconds): Boolean =
        withTimeoutOrNull(timeout) {
            while (subscribedChannels().none { it == channel }) delay(10.milliseconds)
            true
        } ?: false

    /** init bytes followed by the requested segment bodies, in the given order. */
    fun expectedBytes(roomId: Long, generation: Int, throughIndex: Int): ByteArray =
        expectedBytes(roomId, generation, (1..throughIndex).toList())

    fun expectedBytes(roomId: Long, generation: Int, indices: List<Int>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(MockPayloads.initBytes(roomId, generation))
        indices.forEach { out.write(MockPayloads.segmentBytes(roomId, generation, it)) }
        return out.toByteArray()
    }

    override fun close() {
        ownedJobs.forEach { it.cancel() }
        runBlocking {
            sessions.toList().forEach { it.close() }
            server.stop(500, 1000)
        }
        scope.cancel()
    }

    // ── internals ──────────────────────────────────────────────────────────────

    /** Sends a typed push frame to every session subscribed to [channel]. */
    suspend fun push(channel: String, data: JsonObject) {
        recordedPushes += MockPush(channel, data)
        deliver(
            channel,
            buildJsonObject {
                put("push", buildJsonObject {
                    put("channel", channel)
                    put("pub", buildJsonObject { put("data", data) })
                })
            }.toString()
        )
    }

    /** Sends raw text frames (one JSON object per line) to subscribers of [channel]. */
    suspend fun pushRaw(channel: String, text: String) = deliver(channel, text)

    private suspend fun deliver(channel: String, message: String) {
        sessions.filter { it.subscribed.contains(channel) }.forEach { it.send(message) }
    }

    /** Blocks until no session is subscribed to [channel] any more. */
    suspend fun awaitSubscriptionGone(channel: String, timeout: Duration = 5.seconds): Boolean =
        withTimeoutOrNull(timeout) {
            while (subscribedChannels().contains(channel)) delay(20.milliseconds)
            true
        } ?: false

    private fun ApplicationCall.record() {
        recordedRequests += MockRequest(
            method = request.httpMethod.value,
            path = request.path(),
            query = request.queryParameters.entries().associate { it.key to it.value.first() },
            atMs = System.currentTimeMillis()
        )
    }

    /** Returns true when the request was answered by an injected fault. */
    private suspend fun ApplicationCall.applyFault(path: String): Boolean {
        val fault = faults[path]?.poll() ?: return false
        return when (fault) {
            MockFault.NotFound -> {
                respondText("""{"description":"mock not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
                true
            }

            MockFault.ServerError -> {
                respondText("mock server error", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
                true
            }

            is MockFault.Delay -> {
                delay(fault.duration)
                false
            }

            MockFault.Interrupt -> {
                respondBytesWriter(contentType = ContentType.Video.MP4, contentLength = 4096L) {
                    writeFully(ByteArray(16))
                    flush()
                    throw IOException("mock interrupted")
                }
                true
            }
        }
    }

    private fun Application.module() {
        intercept(ApplicationCallPipeline.Monitoring) { call.record() }
        install(WebSockets)

        routing {
            webSocket("/connection/websocket") {
                if (rejectWs.get()) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "rejected"))
                    return@webSocket
                }
                val handle = WsHandle(this)
                sessions += handle
                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) handle.onFrame(frame.readText())
                    }
                } finally {
                    sessions -= handle
                }
            }

            get("/api/front/v3/config/initial") {
                call.respondJson(
                    buildJsonObject {
                        put("initial", buildJsonObject {
                            put("client", buildJsonObject {
                                put("websocket", buildJsonObject { put("token", token) })
                                put("user", buildJsonObject {
                                    put("id", 42)
                                    put("username", "tester")
                                    put("tokens", 100_000)
                                })
                                put("csrfToken", "csrf-token")
                                put("csrfTimestamp", "1700000000")
                            })
                        })
                    }
                )
            }

            get("/api/front/v1/broadcasts/{name}") {
                val name = call.parameters["name"].orEmpty()
                val room = roomsByName[name]
                if (room == null) {
                    call.respondJson(
                        buildJsonObject { put("description", "model not found") },
                        HttpStatusCode.NotFound
                    )
                    return@get
                }
                call.respondJson(
                    buildJsonObject {
                        put("item", buildJsonObject {
                            put("status", room.status)
                            put("modelId", room.id)
                            put("username", room.name)
                            put("settings", buildJsonObject {
                                put("presets", buildJsonArray { room.presets.forEach { add(JsonPrimitive(it)) } })
                                put("fps", room.fps)
                                put("height", room.height)
                            })
                        })
                    }
                )
            }

            get("/api/front/v2/models/{id}/cam") {
                val room = call.parameters["id"]?.toLongOrNull()?.let { rooms[it] }
                if (room == null) {
                    call.respondJson(buildJsonObject { put("description", "model not found") }, HttpStatusCode.NotFound)
                    return@get
                }
                call.respondJson(
                    buildJsonObject {
                        put("cam", buildJsonObject {
                            put("modelToken", room.modelToken)
                            put("userFanClub", buildJsonObject {
                                put("subscription", buildJsonObject {
                                    put("status", if (room.freeSpyAccess) "active" else "none")
                                    put("tier", "tier1")
                                })
                                put("benefits", buildJsonArray {
                                    add(buildJsonObject {
                                        put("id", "freeSpying")
                                        put("tiers", buildJsonObject {
                                            put("tier1", buildJsonObject { put("isActive", room.freeSpyAccess) })
                                        })
                                    })
                                })
                            })
                        })
                        put("user", buildJsonObject {
                            put("user", buildJsonObject {
                                put("ticketRate", room.ticketRate)
                                put("privateRate", room.privateRate)
                            })
                        })
                    }
                )
            }

            post("/api/front/show/models/{id}/groupShows/{userId}") {
                call.parameters["id"]?.toLongOrNull()?.let { rooms[it] }?.modelToken = "show-token"
                call.respondJson(buildJsonObject { put("ok", true) })
            }

            put("/api/front/show/models/{id}/viewers/{userId}/spy") {
                call.parameters["id"]?.toLongOrNull()?.let { rooms[it] }?.modelToken = "spy-token"
                call.respondJson(buildJsonObject { put("ok", true) })
            }

            get("/hls/{roomId}/master/{file}") {
                val roomId = call.parameters["roomId"]!!.toLong()
                if (call.applyFault(call.request.path())) return@get
                if (rooms[roomId] == null) {
                    call.respondText("no room", ContentType.Text.Plain, HttpStatusCode.NotFound)
                    return@get
                }
                call.respondText(masterPlaylist(roomId), ContentType.parse("application/vnd.apple.mpegurl"))
            }

            get("/hls/{roomId}/media/{file}") {
                val roomId = call.parameters["roomId"]!!.toLong()
                if (call.applyFault(call.request.path())) return@get
                val room = rooms[roomId]
                if (room == null) {
                    call.respondText("no room", ContentType.Text.Plain, HttpStatusCode.NotFound)
                    return@get
                }
                val range = MockPayloads.window(room.availableSegments, windowSize)
                call.respondText(
                    MockPayloads.mediaPlaylistText(
                        baseUrl, roomId, room.generation, range.first, range.last, decryptKey
                    ),
                    ContentType.parse("application/vnd.apple.mpegurl")
                )
            }

            get("/media/{roomId}/{file}") {
                val roomId = call.parameters["roomId"]!!.toLong()
                val file = call.parameters["file"].orEmpty()
                if (call.applyFault(call.request.path())) return@get
                val room = rooms[roomId]
                if (room == null) {
                    call.respondText("no room", ContentType.Text.Plain, HttpStatusCode.NotFound)
                    return@get
                }
                val init = Regex("^${roomId}_init_(\\d+)\\.mp4$").find(file)
                if (init != null) {
                    call.respondBytes(MockPayloads.initBytes(roomId, init.groupValues[1].toInt()), ContentType.Video.MP4)
                    return@get
                }
                val segment = Regex("^${roomId}_(\\d+)_[A-Za-z0-9]{16}_\\d{10}\\.mp4$").find(file)
                if (segment == null) {
                    call.respondText("no media", ContentType.Text.Plain, HttpStatusCode.NotFound)
                    return@get
                }
                val segmentId = segment.groupValues[1].toLong()
                val generation = (segmentId / 1000).toInt()
                val index = (segmentId % 1000).toInt()
                segmentDelays["$roomId:$index"]?.let { delay(it) }
                call.respondBytes(MockPayloads.segmentBytes(roomId, generation, index), ContentType.Video.MP4)
                room.completionOrder += index
            }
        }
    }

    private suspend fun ApplicationCall.respondJson(
        body: JsonObject,
        status: HttpStatusCode = HttpStatusCode.OK
    ) {
        respondText(body.toString(), ContentType.Application.Json, status)
    }

    private inner class WsHandle(private val session: DefaultWebSocketServerSession) {
        private val id = connectionIds.incrementAndGet()
        val subscribed: MutableSet<String> = ConcurrentHashMap.newKeySet()
        @Volatile private var authenticated = false

        suspend fun onFrame(text: String) {
            val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            val frameId = json["id"]?.jsonPrimitive?.content ?: "0"

            json["connect"]?.jsonObject?.let { connect ->
                if (connect["token"]?.jsonPrimitive?.content == token) {
                    authenticated = true
                    send("""{"id":$frameId,"connect":{}}""")
                } else {
                    session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "bad token"))
                }
                return
            }

            json["subscribe"]?.jsonObject?.let { subscribe ->
                val channel = subscribe["channel"]?.jsonPrimitive?.content ?: return
                if (!authenticated) {
                    session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthenticated"))
                    return
                }
                subscribed += channel
                send("""{"id":$frameId,"subscribe":{"channel":"$channel"}}""")
                return
            }

            json["unsubscribe"]?.jsonObject?.let { unsubscribe ->
                unsubscribe["channel"]?.jsonPrimitive?.content?.let { subscribed -= it }
            }
        }

        suspend fun send(text: String) {
            runCatching { session.send(Frame.Text(text)) }
        }

        suspend fun close() {
            runCatching {
                session.close(CloseReason(CloseReason.Codes.NORMAL, "mock disconnect"))
            }
        }

        override fun toString(): String = "WsHandle($id, subs=$subscribed)"
    }

    companion object {
        /** Name advertised in `#EXT-X-MOUFLON:PSCH:` and used as the decrypt-key id. */
        const val PSCH_KEY = MockPayloads.DEFAULT_PSCH_KEY
    }
}
