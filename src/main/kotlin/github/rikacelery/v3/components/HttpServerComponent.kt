package github.rikacelery.v3.components

import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.data.HostsConfig
import github.rikacelery.v3.data.Room
import github.rikacelery.v3.data.RoomSettings
import github.rikacelery.v3.data.RuntimeTuning
import github.rikacelery.v3.events.*
import github.rikacelery.v3.ml.PredictionEngine
import github.rikacelery.v3.utils.CdnSelector
import github.rikacelery.v3.utils.ModelSchedule
import io.ktor.http.*
import io.ktor.network.tls.certificates.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.ClosedWriteChannelException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.File
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HttpServerComponent(
    private val port: Int,
    private val eventBus: EventBus,
    private val requestBus: RequestBus,
    private val metricComponent: MetricComponent,
    private val postProcessorComponent: PostProcessorComponent,
    private val scope: CoroutineScope,
    private val mseStore: MseStore = MseStore(),
    private val apiToken: String = "",
    private val runtimeTuning: RuntimeTuning = RuntimeTuning()
) {
    private val logger = LoggerFactory.getLogger("v3.HttpServer")
    private val stopping = AtomicBoolean(false)
    private val dashboardHtml: String by lazy {
        this::class.java.getResource("/vue.html")!!.readText()
    }

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
            installApplication(this, stopEngine = { engine.stop(1000, 5000) })
        }
        engine.start(wait = false)
        logger.info("HTTP server started on port $port")
        return engine
    }

    /**
     * Installs the shared XhRec control routes. Production passes a real engine stop;
     * tests pass a no-op so the route table can be driven through Ktor's test host.
     */
    fun installApplication(application: Application, stopEngine: suspend () -> Unit) {
        application.apply {
            if (apiToken.isNotBlank()) {
                intercept(ApplicationCallPipeline.Plugins) {
                    val path = call.request.uri.substringBefore('?')
                    if (path == "/") return@intercept

                    val token = call.request.queryParameters["token"]
                        ?: call.request.headers["Authorization"]
                            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                            ?.substring(7)
                            ?.trim()
                    if (token != apiToken) {
                        call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                        return@intercept
                    }
                }
            }
            install(CORS) { anyHost() }
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }

            routing {
                get("/") {
                    call.respondText(dashboardHtml, ContentType.Text.Html)
                }
                get("/add") {
                    val name = call.request.queryParameters["name"] ?: ""
                    if (name.isBlank()) return@get call.respondText("Missing name", status = HttpStatusCode.BadRequest)
                    val quality = call.request.queryParameters["quality"] ?: "highest"
                    val active = call.request.queryParameters["active"]?.toBooleanStrictOrNull() ?: false
                    val limit = call.request.queryParameters["limit"]?.toLongOrNull() ?: 0L
                    val autopayTicket = call.request.queryParameters["autopayTicket"]?.toBooleanStrictOrNull() ?: false
                    val autoPaySpy = call.request.queryParameters["autoPaySpy"]?.toBooleanStrictOrNull() ?: false
                    val pkey = call.request.queryParameters["pkey"] ?: ""
                    val sizeBytes = call.request.queryParameters["size"]?.let {
                        try {
                            github.rikacelery.v3.data.SizeStrSerializer.parseSizeString(it)
                        } catch (e: IllegalArgumentException) {
                            logger.error("Invalid size parameter in /add: ${e.message}", e)
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
                                RoomSettings(
                                    quality = quality,
                                    timeLimit = if (limit > 0) limit.seconds else Duration.INFINITE,
                                    sizeLimitBytes = sizeBytes,
                                    autoPayTicket = autopayTicket,
                                    autoPaySpy = autoPaySpy,
                                    pkey = pkey
                                )
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
                        logger.error("Failed to add room '$name'", e)
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

                        eventBus.publish(StopEvent)
                        write("Stop event published.\n"); flush()

                        val sessions = requestBus.request<List<RoomSession>>(GetSessions)
                            .filter { it.state == SessionState.Recording || it.state == SessionState.Fetching }

                        if (sessions.isNotEmpty()) {
                            for (s in sessions) {
                                write("Waiting ${s.roomName}.\n"); flush()
                            }
                            waitSessionsDone(sessions, "Exited", 120_000L) { write(it); flush() }
                        }

                        // let the final FileReady reach the post-processor, then wait for its queue to drain
                        delay(0.5.seconds)
                        // 后处理任务不设超时，等待队列排空后再关停。
                        while (postProcessorComponent.jobs.any { !it.value.isCompleted }) {
                            delay(0.5.seconds)
                        }
                        write("Post-processing drained.\n"); flush()

                        write("OK\n"); flush()
                    }
                    stopEngine()
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
                    requestBus.request<OkResponse>(DeactivateCmd(id))
                    delay(runtimeTuning.httpRestartDelay)
                    requestBus.request<OkResponse>(ActivateRecordingCmd(id))
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
                get("/filter") {
                    val id = call.request.queryParameters["id"]?.toLongOrNull() ?: return@get call.respondText(
                        "Missing id",
                        status = HttpStatusCode.BadRequest
                    )
                    val kind = RecordingFilterKind.fromWire(call.request.queryParameters["kind"])
                        ?: return@get call.respondText(
                            "Invalid kind (expected 'public', 'freespy', 'ticket' or 'paidspy')",
                            status = HttpStatusCode.BadRequest
                        )
                    val v = call.request.queryParameters["v"]?.toBooleanStrictOrNull()
                        ?: return@get call.respondText("Missing v (true/false)", status = HttpStatusCode.BadRequest)
                    requestBus.request<OkResponse>(SetRoomFilter(id, kind, v))
                    persistConfig()
                    call.respondText("Filter ${kind.wireName} set to $v")
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
                        logger.error("Invalid size parameter in /sizelimit: ${e.message}", e)
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
                    val hints = requestBus.request<RecordingHintsResponse>(GetRecordingHints).hints
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
                                        put("startTime", if (isActive) s?.startTime?.toEpochMilli() ?: 0L else 0L)
                                    })
                                    put("listening", s != null || isArmed)
                                    put("room", buildJsonObject {
                                        put("name", r.name); put("id", r.id); put("quality", r.quality)
                                        put("status", r.status)
                                        put("timeLimit", if (r.timeLimit == Duration.INFINITE) 0L else r.timeLimit.inWholeMilliseconds)
                                        put("sizeLimitBytes", r.sizeLimitBytes)
                                        put("recordPublic", r.recordPublic); put("recordFreeSpy", r.recordFreeSpy)
                                        put("autoPayTicket", r.autoPayTicket); put("autoPaySpy", r.autoPaySpy)
                                        hints[r.id]?.let { hint ->
                                            put("hint", buildJsonObject {
                                                put("code", hint.code.wireName)
                                                hint.detail?.let { put("detail", it) }
                                            })
                                        }
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
                get("/config/hosts") {
                    val hosts = requestBus.request<HostsConfigResponse>(GetHostsConfig).hosts
                    val cdnStats = CdnSelector.snapshot()
                    val now = System.currentTimeMillis()
                    call.respond(buildJsonObject {
                        hosts.toJson().forEach { (k, v) -> put(k, v) }
                        put("cdnStats", buildJsonObject {
                            cdnStats.forEach { (host, stat) ->
                                put(host, buildJsonObject {
                                    put("estimatedDurationMs", if (stat.estimatedDurationMs.isNaN()) -1 else stat.estimatedDurationMs.toLong())
                                    put("estimateSource", stat.estimateSource)
                                    put("confidence", stat.confidence)
                                    put("globalEwma", if (stat.globalEwma.isNaN()) -1 else stat.globalEwma.toLong())
                                    put("globalSamples", stat.globalSamples)
                                    put("totalErrors", stat.totalErrors)
                                    put("totalSuccesses", stat.totalSuccesses)
                                    put("failures", stat.failures)
                                    put("coolingDown", stat.cooldownUntil > now)
                                    run {
                                        val ps = CdnSelector.probeSnapshot(host)
                                        put("probeSamples", JsonPrimitive(ps?.samples ?: 0))
                                        put("probeFailures", JsonPrimitive(ps?.failures ?: 0))
                                        put("probeSuccesses", JsonPrimitive(ps?.successes ?: 0))
                                        put("probeDurationMs", JsonPrimitive(ps?.durationMs?.takeIf { !it.isNaN() }?.toLong() ?: -1))
                                    }
                                    put("hourData", buildJsonArray {
                                        for (h in 0 until 24) {
                                            add(buildJsonObject {
                                                put("hour", h)
                                                put("durationMs", if (stat.hourEwma[h].isNaN()) -1 else stat.hourEwma[h].toLong())
                                                put("samples", stat.hourSamples[h])
                                            })
                                        }
                                    })
                                })
                            }
                        })
                    })
                }
                post("/config/hosts") {
                    val params = call.receiveParameters()
                    fun listOf(key: String): List<String> =
                        params[key]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                    val hosts = HostsConfig(
                        platformHosts = listOf("platformHosts"),
                        webSocketHosts = listOf("webSocketHosts"),
                        hlsHosts = listOf("hlsHosts"),
                        hlsMasterHost = params["hlsMasterHost"] ?: "",
                        webHost = params["webHost"] ?: "",
                        previewHost = params["previewHost"] ?: "",
                        thumbHost = params["thumbHost"] ?: ""
                    )
                    try {
                        requestBus.request<OkResponse>(SetHostsConfig(hosts))
                        call.respondText("Hosts config updated")
                    } catch (e: Exception) {
                        logger.error("Failed to update hosts config", e)
                        call.respondText("Error: ${e.message}", status = HttpStatusCode.InternalServerError)
                    }
                }
                // ── Favorites import ───────────────────────────────────────────────
                get("/users") {
                    val users = requestBus.request<UsersResponse>(GetUsers).users
                    // the account cookie stays in the process: only safe fields reach the browser
                    call.respond(buildJsonArray {
                        users.forEach { u ->
                            add(buildJsonObject {
                                put("userId", u.userId)
                                put("username", u.username)
                                put("coins", u.coins)
                            })
                        }
                    })
                }
                get("/favorites/candidates") {
                    val userIds = call.request.queryParameters["users"]
                        ?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.distinct()
                        ?: emptyList()
                    if (userIds.isEmpty()) {
                        return@get call.respondText("Missing users", status = HttpStatusCode.BadRequest)
                    }
                    val candidates = requestBus.request<FavoriteCandidatesResponse>(
                        GetFavoriteCandidates(userIds), timeoutMs = FAVORITES_TIMEOUT_MS
                    ).candidates
                    call.respond(buildJsonArray {
                        candidates.forEach { c ->
                            add(buildJsonObject {
                                put("modelId", c.modelId)
                                put("name", c.name)
                                put("existing", c.existing)
                            })
                        }
                    })
                }
                post("/favorites/import") {
                    val ids = call.receiveParameters()["ids"]
                        ?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.distinct()
                        ?: emptyList()
                    if (ids.isEmpty()) {
                        return@post call.respondText("Missing ids", status = HttpStatusCode.BadRequest)
                    }
                    val added = requestBus.request<FavoritesImportResponse>(
                        ImportFavorites(ids), timeoutMs = FAVORITES_TIMEOUT_MS
                    ).added
                    persistConfig()
                    call.respondText(
                        if (added.isEmpty()) "No favorites imported"
                        else "Imported ${added.size} room(s): ${added.joinToString(", ")}"
                    )
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
                        } catch (e: ClosedWriteChannelException) {
                            // client (browser) closed the preview — normal, not an error
                            logger.debug("SSE client disconnected for room $id")
                            return@respondOutputStream
                        } catch (e: Exception) {
                            logger.error("SSE stream error for room $id: ${e.message}", e)
                            return@respondOutputStream
                        } finally {
                            mseStore.unsubscribe(id, ch)
                        }
                    }
                }
                get("/model/schedule") {
                    val name = call.request.queryParameters["name"] ?: ""
                    val idParam = call.request.queryParameters["id"] ?: ""

                    // Resolve roomId from name or id
                    val roomId: Long = when {
                        idParam.isNotEmpty() -> idParam.toLongOrNull() ?: run {
                            call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", JsonPrimitive("invalid id")) })
                            return@get
                        }
                        name.isNotEmpty() -> {
                            val rooms = requestBus.request<List<Room>>(GetRooms)
                            val room = rooms.find { it.name == name }
                            if (room == null) {
                                call.respond(HttpStatusCode.NotFound, buildJsonObject { put("error", JsonPrimitive("room not found")) })
                                return@get
                            }
                            room.id
                        }
                        else -> {
                            call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", JsonPrimitive("name or id parameter required")) })
                            return@get
                        }
                    }

                    val snapshot = ModelSchedule.snapshot(roomId)
                    if (snapshot == null) {
                        call.respond(buildJsonObject {
                            put("roomId", JsonPrimitive(roomId))
                            put("totalRecordings", JsonPrimitive(0))
                            put("message", JsonPrimitive("No data available"))
                        })
                        return@get
                    }

                    call.respond(buildJsonObject {
                        put("roomId", JsonPrimitive(snapshot.roomId))
                        put("totalRecordings", JsonPrimitive(snapshot.totalCount))
                        put("lastStartTime", JsonPrimitive(snapshot.lastStartTime))
                        put("hourDistribution", buildJsonArray {
                            snapshot.hourDistribution.forEach { add(JsonPrimitive((it * 100).toInt())) }
                        })
                        put("topHours", buildJsonArray {
                            snapshot.topHours.forEach { (h, p) ->
                                add(buildJsonObject {
                                    put("hour", JsonPrimitive(h))
                                    put("probability", JsonPrimitive((p * 100).toInt()))
                                })
                            }
                        })
                        snapshot.nextPredictedHour?.let { put("nextPredictedHour", JsonPrimitive(it)) }
                        put("recentCount", JsonPrimitive(snapshot.recentCount))
                    })
                }

                get("/model/schedule/all") {
                    val rooms = requestBus.request<List<Room>>(GetRooms)
                    val nameById = rooms.associate { it.id to it.name }
                    val roomIds = ModelSchedule.getAllRoomIds()

                    call.respond(buildJsonArray {
                        roomIds.forEach { roomId ->
                            ModelSchedule.snapshot(roomId)?.let { snapshot ->
                                add(buildJsonObject {
                                    put("roomId", JsonPrimitive(snapshot.roomId))
                                    put("name", JsonPrimitive(nameById[roomId] ?: ""))
                                    put("totalRecordings", JsonPrimitive(snapshot.totalCount))
                                    put("topHours", buildJsonArray {
                                        snapshot.topHours.take(3).forEach { (h, _) -> add(JsonPrimitive(h)) }
                                    })
                                    snapshot.nextPredictedHour?.let { put("nextPredictedHour", JsonPrimitive(it)) }
                                })
                            }
                        }
                    })
                }

                get("/ml/status") {
                    val s = PredictionEngine.status()
                    call.respond(buildJsonObject {
                        put("dataDir", JsonPrimitive(s.dataDir))
                        put("cdnSamples", JsonPrimitive(s.cdnSamples))
                        put("scheduleSamples", JsonPrimitive(s.scheduleSamples))
                        put("cdnModelReady", JsonPrimitive(s.cdnModelReady))
                        put("scheduleModelReady", JsonPrimitive(s.scheduleModelReady))
                        put("cdnTrees", JsonPrimitive(s.cdnTrees))
                        put("scheduleTrees", JsonPrimitive(s.scheduleTrees))
                    })
                }

                get("/ml/train") {
                    withContext(Dispatchers.IO) { PredictionEngine.trainAll() }
                    val s = PredictionEngine.status()
                    call.respond(buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("cdnModelReady", JsonPrimitive(s.cdnModelReady))
                        put("scheduleModelReady", JsonPrimitive(s.scheduleModelReady))
                        put("cdnSamples", JsonPrimitive(s.cdnSamples))
                        put("scheduleSamples", JsonPrimitive(s.scheduleSamples))
                    })
                }

                get("/cdn/clear-cooldown") {
                    val host = call.request.queryParameters["host"] ?: ""
                    CdnSelector.clearCooldown(host)
                    call.respondText("ok")
                }

                get("/stop-server") {
                    if (stopping.getAndSet(true)) {
                        call.respondText("Already shutting down...", status = HttpStatusCode.NotAcceptable)
                        return@get
                    }
                    call.respondTextWriter {
                        write("Stopping server...\n"); flush()

                        eventBus.publish(StopEvent)
                        write("Stop event published.\n"); flush()

                        val sessions = requestBus.request<List<RoomSession>>(GetSessions)
                            .filter { it.state == SessionState.Recording || it.state == SessionState.Fetching }

                        if (sessions.isNotEmpty()) {
                            for (s in sessions) {
                                write("Cancelling ${s.roomName}.\n"); flush()
                                requestBus.request<OkResponse>(DeactivateCmd(s.roomId))
                            }
                            waitSessionsDone(sessions, "Cancelled", 120_000L) { write(it); flush() }
                        }
                        delay(2.seconds)
                        // 动态排空后处理队列: 关停期间新落盘的文件(FileReady)也会被纳入
                        // 等待 —— 若只等一次快照, 快照外的任务会在 stop() 时被取消,
                        // 留下半成品文件。
                        // 后处理任务不设超时，必须等待结束后才能继续关停。
                        val seen = mutableSetOf<String>()
                        while (true) {
                            val running = postProcessorComponent.jobs.filter { !it.value.isCompleted }
                            if (running.isEmpty()) break
                            running.keys.filter { seen.add(it) }
                                .forEach { write("Waiting ${it}.\n"); flush() }
                            var remain = running.size
                            for ((key, job) in running) {
                                job.join()
                                remain--
                                write("Processed $key. remain: $remain\n"); flush()
                            }
                        }

                        write("OK\n"); flush()
                    }
                    eventBus.publish("ServerShutdown")
                }
            }
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
        } catch (e: TimeoutCancellationException) {
            logger.error("Timeout waiting for sessions", e)
            onProgress("Timeout waiting for sessions.\n")
        }
    }

    companion object {
        /** Platform timeout for a favorites command: one request per account plus name lookups. */
        private const val FAVORITES_TIMEOUT_MS = 120_000L

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
