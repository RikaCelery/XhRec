package github.rikacelery.v3.components

import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.events.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import github.rikacelery.v3.data.Room
import github.rikacelery.v3.components.RoomSession
import github.rikacelery.v3.components.SessionState
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

class HttpServerComponent(
    private val port: Int,
    private val eventBus: EventBus,
    private val requestBus: RequestBus,
    private val metricComponent: MetricComponent,
    private val scope: CoroutineScope
) {
    private val logger = LoggerFactory.getLogger("v3.HttpServer")

    fun start(): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
        val engine = embeddedServer(CIO, port = port) {
            install(CORS) { anyHost() }
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }

            routing {
                get("/") {
                    call.respondText(this::class.java.getResource("/vue.html")!!.readText(), ContentType.Text.Html)
                }
                get("/add") {
                    val name = call.request.queryParameters["name"] ?: ""
                    if (name.isBlank()) return@get call.respondText("Missing name", status = HttpStatusCode.BadRequest)
                    val quality = call.request.queryParameters["quality"] ?: "720p"
                    try { call.respondText("Room added: ${requestBus.request<RoomNameResponse>(AddRoom(name, quality), timeoutMs = 30_000).name}") }
                    catch (e: Exception) { call.respondText("Error: ${e.message}", status = HttpStatusCode.InternalServerError) }
                }
                get("/start") {
                    val roomId = call.request.queryParameters["id"]?.toLongOrNull() ?: 0
                    try { requestBus.request<OkResponse>(StartRecordingCmd(roomId)); call.respondText("Recording started for $roomId") }
                    catch (e: Exception) { call.respondText("Error: ${e.message}", status = HttpStatusCode.InternalServerError) }
                }
                get("/stop") {
                    val roomId = call.request.queryParameters["id"]?.toLongOrNull() ?: 0
                    try { requestBus.request<OkResponse>(StopRecordingCmd(roomId)); call.respondText("Recording stopped for $roomId") }
                    catch (e: Exception) { call.respondText("Error: ${e.message}", status = HttpStatusCode.InternalServerError) }
                }
                get("/graceful-stop") {
                    call.respondText("OK")
                    scope.launch { delay(1000); eventBus.publish("ServerShutdown") }
                }
                get("/remove") {
                    val id = call.request.queryParameters["id"]?.toLongOrNull() ?: return@get call.respondText("Missing id", status = HttpStatusCode.BadRequest)
                    requestBus.request<OkResponse>(RemoveRoom(id))
                    call.respondText("Removed")
                }
                get("/restart") {
                    val id = call.request.queryParameters["id"]?.toLongOrNull() ?: return@get call.respondText("Missing id", status = HttpStatusCode.BadRequest)
                    requestBus.request<OkResponse>(StopRecordingCmd(id))
                    delay(500)
                    requestBus.request<OkResponse>(StartRecordingCmd(id))
                    call.respondText("Restarted")
                }
                get("/break") {
                    val id = call.request.queryParameters["id"]?.toLongOrNull() ?: return@get call.respondText("Missing id", status = HttpStatusCode.BadRequest)
                    requestBus.request<OkResponse>(BreakCmd(id))
                    call.respondText("Break signaled")
                }
                get("/activate") {
                    val id = call.request.queryParameters["id"]?.toLongOrNull() ?: return@get call.respondText("Missing id", status = HttpStatusCode.BadRequest)
                    requestBus.request<OkResponse>(ActivateRecordingCmd(id))
                    call.respondText("Activated")
                }
                get("/deactivate") {
                    val id = call.request.queryParameters["id"]?.toLongOrNull() ?: return@get call.respondText("Missing id", status = HttpStatusCode.BadRequest)
                    requestBus.request<OkResponse>(DeactivateCmd(id))
                    call.respondText("Deactivated")
                }
                get("/quality") {
                    val id = call.request.queryParameters["id"]?.toLongOrNull() ?: return@get call.respondText("Missing id", status = HttpStatusCode.BadRequest)
                    val q = call.request.queryParameters["q"] ?: "720p"
                    requestBus.request<OkResponse>(SetRoomQuality(id, q))
                    call.respondText("Quality set to $q")
                }
                get("/autopay") {
                    val id = call.request.queryParameters["id"]?.toLongOrNull() ?: return@get call.respondText("Missing id", status = HttpStatusCode.BadRequest)
                    val v = call.request.queryParameters["v"]?.toBooleanStrictOrNull() ?: return@get call.respondText("Missing v (true/false)", status = HttpStatusCode.BadRequest)
                    requestBus.request<OkResponse>(SetRoomAutoPay(id, v))
                    call.respondText("Autopay set to $v")
                }
                get("/limit") {
                    val id = call.request.queryParameters["id"]?.toLongOrNull() ?: return@get call.respondText("Missing id", status = HttpStatusCode.BadRequest)
                    val v = call.request.queryParameters["v"]?.toLongOrNull() ?: return@get call.respondText("Missing v (seconds)", status = HttpStatusCode.BadRequest)
                    requestBus.request<OkResponse>(SetRoomTimeLimit(id, v))
                    call.respondText("Time limit set to ${v}s")
                }
                get("/sizelimit") {
                    val id = call.request.queryParameters["id"]?.toLongOrNull() ?: return@get call.respondText("Missing id", status = HttpStatusCode.BadRequest)
                    val v = call.request.queryParameters["v"] ?: return@get call.respondText("Missing v", status = HttpStatusCode.BadRequest)
                    requestBus.request<OkResponse>(SetRoomSizeLimit(id, v.toLong()))
                    call.respondText("Size limit set to $v")
                }
                get("/list") {
                    val rooms = requestBus.request<List<Room>>(GetRooms)
                    val sessions = requestBus.request<List<RoomSession>>(GetSessions)
                    call.respond(buildJsonArray {
                        rooms.forEach { r ->
                            val s = sessions.find { it.roomId == r.id }
                            add(buildJsonArray {
                                add(r.status)
                                add(if (s != null) "listening" else "")
                                add(if (s?.state == SessionState.Recording) "recording" else "")
                                add(r.name); add(r.id.toString()); add(r.quality)
                                add(if (r.timeLimitMs > 0) (r.timeLimitMs / 1000).toString() else "0")
                            })
                        }
                    })
                }
                get("/listv2") {
                    val rooms = requestBus.request<List<Room>>(GetRooms)
                    val sessions = requestBus.request<List<RoomSession>>(GetSessions)
                    call.respond(buildJsonArray {
                        rooms.forEach { r ->
                            val s = sessions.find { it.roomId == r.id }
                            add(buildJsonObject {
                                put("session", buildJsonObject {
                                    put("status", s?.state?.name ?: "")
                                    put("active", s?.state == SessionState.Recording)
                                })
                                put("listening", s != null)
                                put("room", buildJsonObject {
                                    put("name", r.name); put("id", r.id); put("quality", r.quality)
                                    put("status", r.status)
                                    put("timeLimitMs", r.timeLimitMs); put("sizeLimitBytes", r.sizeLimitBytes)
                                    put("autoPay", r.autoPay)
                                })
                            })
                        }
                    })
                }
                get("/status") {
                    val sessions = requestBus.request<List<RoomSession>>(GetSessions)
                    call.respond(buildJsonObject {
                        sessions.filter { it.state == SessionState.Recording || it.state == SessionState.Fetching }
                            .forEach { s -> put(s.roomName, s.state.name) }
                    })
                }
                get("/recorders") {
                    val rooms = requestBus.request<List<Room>>(GetRooms)
                    call.respond(buildJsonArray {
                        rooms.forEach { r ->
                            add(buildJsonObject {
                                put("name", r.name); put("id", r.id); put("quality", r.quality)
                            })
                        }
                    })
                }
                get("/metrics") {
                    call.respondText(metricComponent.prometheusText())
                }
                get("/stop-server") {
                    call.respondText("Stopping")
                    engine.stop(500, 1000)
                }
            }
        }
        engine.start(wait = false)
        logger.info("HTTP server started on port $port")
        return engine
    }
}
