package github.rikacelery.v3.components

import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.data.Room
import github.rikacelery.v3.events.*
import io.ktor.http.*
import io.ktor.network.tls.certificates.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.File
import java.security.KeyStore
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class HttpServerComponent(
    private val port: Int,
    private val eventBus: EventBus,
    private val requestBus: RequestBus,
    private val metricComponent: MetricComponent,
    private val postProcessorComponent: PostProcessorComponent,
    private val scope: CoroutineScope,
    private val mseStore: MseStore = MseStore()
) {
    private val logger = LoggerFactory.getLogger("v3.HttpServer")
    private val stopping = AtomicBoolean(false)

    fun start(): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
        val keyStoreFile = File("xhrec.keystore")
        if (!keyStoreFile.exists()) {
            val ks = buildKeyStore {
                certificate("xhrec") {
                    password = "changeit"
                    domains = listOf("127.0.0.1", "0.0.0.0", "localhost")
                }
            }
            ks.saveToFile(keyStoreFile, "changeit")
            logger.info("Generated self-signed certificate: {}", keyStoreFile.absolutePath)
        }
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            keyStoreFile.inputStream().use { load(it, "changeit".toCharArray()) }
        }

        lateinit var engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
        engine = embeddedServer(Netty, applicationEnvironment {
            log = LoggerFactory.getLogger("ktor.application")
        }, {
            // engine config: SSL
            sslConnector(
                keyStore = keyStore,
                keyAlias = "xhrec",
                keyStorePassword = { "changeit".toCharArray() },
                privateKeyPassword = { "changeit".toCharArray() }
            ) {
                port = this@HttpServerComponent.port
                keyStorePath = keyStoreFile
            }
        }) {
            install(CORS) { anyHost() }
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }

            routing {
                get("/") {
                    call.respondText(this::class.java.getResource("/vue.html")!!.readText(), ContentType.Text.Html)
                }
                get("/add") {
                    val name = call.request.queryParameters["name"] ?: ""
                    if (name.isBlank()) return@get call.respondText("Missing name", status = HttpStatusCode.BadRequest)
                    val quality = call.request.queryParameters["quality"] ?: "highest"
                    val active = call.request.queryParameters["active"]?.toBooleanStrictOrNull() ?: false
                    val limit = call.request.queryParameters["limit"]?.toLongOrNull() ?: 0L
                    val autopay = call.request.queryParameters["autopay"]?.toBooleanStrictOrNull() ?: false
                    val pkey = call.request.queryParameters["pkey"] ?: ""
                    val sizeBytes = call.request.queryParameters["size"]?.let {
                        try {
                            github.rikacelery.v3.data.SizeStrSerializer.parseSizeString(it)
                        } catch (e: IllegalArgumentException) {
                            return@get call.respondText(
                                "Invalid size: ${e.message}",
                                status = HttpStatusCode.BadRequest
                            )
                        }
                    } ?: 0L
                    try {
                        val resp = requestBus.request<RoomNameResponse>(
                            AddRoom(
                                name,
                                quality,
                                pkey,
                                if (limit > 0) limit.seconds else Duration.INFINITE,
                                sizeBytes,
                                autopay
                            ), timeoutMs = 30_000
                        )
                        if (active) {
                            val rooms = requestBus.request<List<Room>>(GetRooms)
                            val added = rooms.find { it.name == resp.name }
                            if (added != null) {
                                requestBus.request<OkResponse>(ActivateRecordingCmd(added.id))
                            }
                        }
                        persistConfig()
                        call.respondText("Room added: ${resp.name}")
                    } catch (e: Exception) {
                        call.respondText("Error: ${e.message}", status = HttpStatusCode.InternalServerError)
                    }
                }
                get("/start") {
                    val roomId = call.request.queryParameters["id"]?.toLongOrNull() ?: 0
                    try {
                        requestBus.request<OkResponse>(StartRecordingCmd(roomId))
                        persistConfig()
                        call.respondText("Recording started for $roomId")
                    } catch (e: Exception) {
                        call.respondText("Error: ${e.message}", status = HttpStatusCode.InternalServerError)
                    }
                }
                get("/stop") {
                    val roomId = call.request.queryParameters["id"]?.toLongOrNull() ?: 0
                    try {
                        requestBus.request<OkResponse>(StopRecordingCmd(roomId))
                        call.respondText("Recording stopped for $roomId")
                    } catch (e: Exception) {
                        call.respondText("Error: ${e.message}", status = HttpStatusCode.InternalServerError)
                    }
                }
                get("/graceful-stop") {
                    if (stopping.getAndSet(true)) {
                        call.respondText("Already shutting down...", status = HttpStatusCode.NotAcceptable)
                        return@get
                    }
                    call.respondTextWriter {
                        write("Stopping server...\n"); flush()

                        requestBus.request<OkResponse>(ShutdownCmd)
                        write("Canceled scheduler.\n"); flush()

                        val sessions = requestBus.request<List<RoomSession>>(GetSessions)
                            .filter { it.state == SessionState.Recording || it.state == SessionState.Fetching }

                        if (sessions.isNotEmpty()) {
                            for (s in sessions) {
                                write("Waiting ${s.roomName}.\n"); flush()
                                requestBus.request<OkResponse>(StopRecordingCmd(s.roomId))
                            }
                            waitSessionsDone(sessions, "Exited", 120_000L) { write(it); flush() }
                        }
                        write("OK\n"); flush()
                    }
                    engine.stop(1000, 5000)
                    eventBus.publish("ServerShutdown")
                }
                get("/remove") {
                    val id = call.request.queryParameters["id"]?.toLongOrNull() ?: return@get call.respondText(
                        "Missing id",
                        status = HttpStatusCode.BadRequest
                    )
                    requestBus.request<OkResponse>(RemoveRoom(id))
                    persistConfig()
                    call.respondText("Removed")
                }
                get("/restart") {
                    val id = call.request.queryParameters["id"]?.toLongOrNull() ?: return@get call.respondText(
                        "Missing id",
                        status = HttpStatusCode.BadRequest
                    )
                    requestBus.request<OkResponse>(StopRecordingCmd(id))
                    delay(0.5.seconds)
                    requestBus.request<OkResponse>(StartRecordingCmd(id))
                    call.respondText("Restarted")
                }
                get("/break") {
                    val id = call.request.queryParameters["id"]?.toLongOrNull() ?: return@get call.respondText(
                        "Missing id",
                        status = HttpStatusCode.BadRequest
                    )
                    requestBus.request<OkResponse>(BreakCmd(id, reason = EndReason.NewInit))
                    call.respondText("Break signaled")
                }
                get("/activate") {
                    val id = call.request.queryParameters["id"]?.toLongOrNull() ?: return@get call.respondText(
                        "Missing id",
                        status = HttpStatusCode.BadRequest
                    )
                    requestBus.request<OkResponse>(ActivateRecordingCmd(id))
                    persistConfig()
                    call.respondText("Activated")
                }
                get("/deactivate") {
                    val id = call.request.queryParameters["id"]?.toLongOrNull() ?: return@get call.respondText(
                        "Missing id",
                        status = HttpStatusCode.BadRequest
                    )
                    requestBus.request<OkResponse>(DeactivateCmd(id))
                    persistConfig()
                    call.respondText("Deactivated")
                }
                get("/quality") {
                    val id = call.request.queryParameters["id"]?.toLongOrNull() ?: return@get call.respondText(
                        "Missing id",
                        status = HttpStatusCode.BadRequest
                    )
                    val q = call.request.queryParameters["q"] ?: "highest"
                    requestBus.request<OkResponse>(SetRoomQuality(id, q))
                    persistConfig()
                    call.respondText("Quality set to $q")
                }
                get("/autopay") {
                    val id = call.request.queryParameters["id"]?.toLongOrNull() ?: return@get call.respondText(
                        "Missing id",
                        status = HttpStatusCode.BadRequest
                    )
                    val v = call.request.queryParameters["v"]?.toBooleanStrictOrNull()
                        ?: return@get call.respondText("Missing v (true/false)", status = HttpStatusCode.BadRequest)
                    requestBus.request<OkResponse>(SetRoomAutoPay(id, v))
                    persistConfig()
                    call.respondText("Autopay set to $v")
                }
                get("/limit") {
                    val id = call.request.queryParameters["id"]?.toLongOrNull() ?: return@get call.respondText(
                        "Missing id",
                        status = HttpStatusCode.BadRequest
                    )
                    val v = call.request.queryParameters["v"]?.toLongOrNull()
                        ?: return@get call.respondText("Missing v (seconds)", status = HttpStatusCode.BadRequest)
                    requestBus.request<OkResponse>(SetRoomTimeLimit(id, if (v == 0L) Duration.INFINITE else v.seconds))
                    persistConfig()
                    call.respondText("Time limit set to ${v}s")
                }
                get("/sizelimit") {
                    val id = call.request.queryParameters["id"]?.toLongOrNull() ?: return@get call.respondText(
                        "Missing id",
                        status = HttpStatusCode.BadRequest
                    )
                    val v = call.request.queryParameters["v"] ?: return@get call.respondText(
                        "Missing v",
                        status = HttpStatusCode.BadRequest
                    )
                    val bytes = try {
                        github.rikacelery.v3.data.SizeStrSerializer.parseSizeString(v)
                    } catch (e: IllegalArgumentException) {
                        return@get call.respondText(
                            "Invalid size format: ${e.message}",
                            status = HttpStatusCode.BadRequest
                        )
                    }
                    requestBus.request<OkResponse>(SetRoomSizeLimit(id, bytes))
                    persistConfig()
                    call.respondText("Size limit set to $v")
                }
                get("/list") {
                    val rooms = requestBus.request<List<Room>>(GetRooms)
                    val sessions = requestBus.request<List<RoomSession>>(GetSessions)
                    val armedIds = requestBus.request<List<Long>>(GetArmedRoomIds).toSet()
                    call.respond(buildJsonArray {
                        rooms.forEach { r ->
                            val s = sessions.find { it.roomId == r.id }
                            val isListening = s != null || r.id in armedIds
                            add(buildJsonArray {
                                add(r.status)
                                add(if (isListening) "listening" else "")
                                add(if (s?.state == SessionState.Recording) "recording" else "")
                                add(r.name); add(r.id.toString()); add(r.quality)
                                add(if (r.timeLimit != Duration.INFINITE) r.timeLimit.inWholeSeconds.toString() else "0")
                            })
                        }
                    })
                }
                get("/status") {
                    val statuses = requestBus.request<Map<Long, Map<String, Any>>>(GetRoomDetailedStatus)
                    val sessions = requestBus.request<List<RoomSession>>(GetSessions)
                    val nameById = sessions.associate { it.roomId to it.roomName }
                    val activeRooms = statuses.filter { (_, v) -> hasRecentActivity(v) }
                    call.respond(buildJsonObject {
                        activeRooms.forEach { (roomId, data) ->
                            put(nameById[roomId] ?: roomId.toString(), anyToJsonElement(data))
                        }
                    })
                }
                get("/dashboard") {
                    val rooms = requestBus.request<List<Room>>(GetRooms)
                    val statuses = requestBus.request<Map<Long, Map<String, Any>>>(GetRoomDetailedStatus)
                    val sessions = requestBus.request<List<RoomSession>>(GetSessions)
                    val armedIds = requestBus.request<List<Long>>(GetArmedRoomIds).toSet()
                    val metrics = metricComponent.prometheusText()

                    val json = Json { encodeDefaults = true }
                    val nameById = sessions.associate { it.roomId to it.roomName }

                    call.respond(buildJsonObject {
                        put("rooms", buildJsonArray { rooms.forEach { add(json.encodeToJsonElement(it)) } })
                        put("statuses", buildJsonObject {
                            statuses.filter { (_, v) -> hasRecentActivity(v) }.forEach { (roomId, data) ->
                                put(nameById[roomId] ?: roomId.toString(), anyToJsonElement(data))
                            }
                        })
                        put("listv2", buildJsonArray {
                            rooms.forEach { r ->
                                val s = sessions.find { it.roomId == r.id }
                                val isArmed = r.id in armedIds
                                val isActive = s?.state == SessionState.Fetching || s?.state == SessionState.Recording
                                add(buildJsonObject {
                                    put("session", buildJsonObject {
                                        put("status", when {
                                            s != null && isActive -> s.state.name
                                            isArmed -> "Listening"
                                            else -> s?.state?.name ?: ""
                                        })
                                        put("active", s?.state == SessionState.Recording)
                                        put("quality", s?.quality ?: "")
                                    })
                                    put("listening", s != null || isArmed)
                                    put("room", buildJsonObject {
                                        put("name", r.name); put("id", r.id); put("quality", r.quality)
                                        put("status", r.status)
                                        put("timeLimit", if (r.timeLimit == Duration.INFINITE) 0L else r.timeLimit.inWholeMilliseconds)
                                        put("sizeLimitBytes", r.sizeLimitBytes); put("autoPay", r.autoPay)
                                    })
                                })
                            }
                        })
                        put("metrics", metrics)
                    })
                }
                get("/metrics") {
                    call.respondText(metricComponent.prometheusText())
                }
                get("/mask/status") {
                    val status = requestBus.request<ConfigResponse>(GetMaskStatus).value
                    call.respondText(status.toString())
                }
                get("/mask/toggle") {
                    val status = requestBus.request<ConfigResponse>(ToggleMask).value
                    persistConfig()
                    call.respondText(status.toString())
                }
                get("/mse/live") {
                    val id = call.request.queryParameters["id"]?.toLongOrNull()
                        ?: return@get call.respondText("Missing id", status = HttpStatusCode.BadRequest)

                    call.response.header("Cache-Control", "no-cache, no-store")
                    call.respondOutputStream(ContentType.Video.MP4) {
                        val ch = mseStore.subscribe(id)
                        try {
                            for (chunk in ch) {
                                if (!coroutineContext.isActive) break
                                when (chunk) {
                                    is MseStore.SseChunk.Init -> write(chunk.data)
                                    is MseStore.SseChunk.Seg -> {
                                        write(chunk.data)
                                    }
                                    is MseStore.SseChunk.Meta -> {} // skip
                                }
                                flush()
                            }
                        }catch(_:Exception){
                            return@respondOutputStream
                        } finally {
                            mseStore.unsubscribe(id, ch)
                        }
                    }
                }
                get("/stop-server") {
                    if (stopping.getAndSet(true)) {
                        call.respondText("Already shutting down...", status = HttpStatusCode.NotAcceptable)
                        return@get
                    }
                    call.respondTextWriter {
                        write("Stopping server...\n"); flush()

                        requestBus.request<OkResponse>(ShutdownCmd)
                        write("Canceled scheduler.\n"); flush()

                        val sessions = requestBus.request<List<RoomSession>>(GetSessions)
                            .filter { it.state == SessionState.Recording || it.state == SessionState.Fetching }

                        if (sessions.isNotEmpty()) {
                            for (s in sessions) {
                                write("Cancelling ${s.roomName}.\n"); flush()
                                requestBus.request<OkResponse>(StopRecordingCmd(s.roomId))
                            }
                            waitSessionsDone(sessions, "Cancelled", 120_000L) { write(it); flush() }
                        }
                        delay(2.seconds)
                        val processors = postProcessorComponent.jobs.filter { !it.value.isCompleted }
                        for (s in processors) {
                            write("Waiting ${s.key}.\n"); flush()
                            waitProcessorDone(
                                processors,
                                "Processed",
                                3.minutes.inWholeMilliseconds
                            ) { write(it); flush() }
                        }

                        write("OK\n"); flush()
                    }
                    eventBus.publish("ServerShutdown")
                }
            }
        }
        engine.start(wait = false)
        logger.info("HTTP server started on port $port")
        return engine
    }

    suspend fun waitProcessorDone(
        sessions: Map<String, Job>, verb: String, timeoutMs: Long,
        onProgress: suspend (String) -> Unit
    ) {
        val stopped = mutableSetOf<String>()
        var remaining = sessions.size
        try {
            withTimeout(timeoutMs.milliseconds) {
                while (remaining > 0) {
                    delay(0.5.seconds)
                    val current = postProcessorComponent.jobs.filter { !it.value.isCompleted }
                    for (s in sessions) {
                        if (s.key !in stopped) {
                            val cur = current[s.key]
                            if (cur == null || cur.isCompleted) {
                                stopped.add(s.key)
                                remaining--
                                onProgress("$verb ${s.key}. remain: $remaining\n")
                            }
                        }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            onProgress("Timeout waiting for processors.\n")
        }
    }

    private fun persistConfig() {
        scope.launch { eventBus.publish(PersistConfig) }
    }

    private suspend fun waitSessionsDone(
        sessions: List<RoomSession>, verb: String, timeoutMs: Long,
        onProgress: suspend (String) -> Unit
    ) {
        val stopped = mutableSetOf<Long>()
        var remaining = sessions.size
        try {
            withTimeout(timeoutMs.milliseconds) {
                while (remaining > 0) {
                    delay(0.5.seconds)
                    val current = requestBus.request<List<RoomSession>>(GetSessions)
                    for (s in sessions) {
                        if (s.roomId !in stopped) {
                            val cur = current.find { it.roomId == s.roomId }
                            if (cur == null || (cur.state != SessionState.Recording && cur.state != SessionState.Fetching)) {
                                stopped.add(s.roomId)
                                remaining--
                                onProgress("$verb ${s.roomName}. remain: $remaining\n")
                            }
                        }
                    }
                }
            }
            delay(0.5.seconds) // let CutPoint drain through pipeline
        } catch (_: TimeoutCancellationException) {
            onProgress("Timeout waiting for sessions.\n")
        }
    }

    companion object {
        private fun hasRecentActivity(data: Map<String, Any>): Boolean {
            val running = data["running"] as? Map<*, *>
            val success = (data["success"] as? Number)?.toInt() ?: 0
            return running?.isNotEmpty() == true || success > 0
        }

        private fun anyToJsonElement(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is JsonElement -> value
            is Map<*, *> -> buildJsonObject {
                value.forEach { (k, v) -> put(k.toString(), anyToJsonElement(v)) }
            }

            is Collection<*> -> buildJsonArray {
                value.forEach { add(anyToJsonElement(it)) }
            }

            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
    }
}
