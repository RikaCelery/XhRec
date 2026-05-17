package github.rikacelery

import github.rikacelery.utils.ClientManager
import github.rikacelery.utils.withRetryOrNull
import github.rikacelery.v2.API
import github.rikacelery.v2.EventDispatcher
import github.rikacelery.v2.Scheduler
import github.rikacelery.v2.metric.Metric
import github.rikacelery.v2.postprocessors.PostProcessor
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.internal.synchronized
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.apache.commons.cli.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


var HOST = "stripchat.com"
val BUILTIN by lazy {
    setOf(
        "# https://$HOST/ChangeToYourModel q:1080p limit:120",
    )
}

private fun extract(text: String, regex: Regex, default: String): String {
    return regex.find(text)?.groupValues?.get(1)?.ifBlank { default } ?: default
}

val rootLogger: Logger = LoggerFactory.getLogger("github.rikacelery.MainKt")

@OptIn(InternalCoroutinesApi::class)
fun main1(vararg args: String): Unit = runBlocking {

    if ((System.getenv("http_proxy") ?: System.getenv("HTTP_PROXY")) != null) {
        rootLogger.info("Testing proxy")
        runCatching {
            ClientManager.getClient("test").get(System.getenv("http_proxy") ?: System.getenv("HTTP_PROXY")) {
                expectSuccess = false
            }
            rootLogger.info("Proxy connect success.")
            ClientManager.getProxiedClient("test").get("https://$HOST") {
                expectSuccess = false
            }
            rootLogger.info("Proxy test (https://$HOST) success.")
        }.onFailure {
            rootLogger.info("Proxy test failed. $it")
        }
    }
    val parser: CommandLineParser = DefaultParser()
    val options = Options()
    options.addOption("post", true, "Post Processor Config File [default: postprocessor.json]")
    options.addOption("f", "file", true, "Room List File [default: list.conf]")
    options.addOption("o", "output", true, "Output Dir [default: out]")
    options.addOption("t", "tmp", true, "Temp Dir [default: tmp]")
    options.addOption("p", "port", true, "Server Port [default: 8090]")
    options.addOption("u", "users", true, "Configuration File, one cookie per line [default: users.txt]")

    val commandLine: CommandLine = try {
        parser.parse(options, args)
    } catch (_: ParseException) {
        val formatter = HelpFormatter()
        formatter.printHelp("CommandLineParameters", options)
        return@runBlocking
    }
    if (commandLine.hasOption("h")) {
        val formatter = HelpFormatter()
        formatter.printHelp("CommandLineParameters", options)
    }


    val file = File(commandLine.getOptionValue("post", "postprocessor.json"))
    if (file.exists().not()) {
        file.writeText(
            """{
  "default": [
    {
      "type": "fix_stamp",
      "output": "/out"
    }
  ]
}"""
        )
    }
    PostProcessor.loadConfig(file)
    val jobFile = File(commandLine.getOptionValue("f", "list.conf"))
    rootLogger.info("jobfile: {}, postprocessor:{}", jobFile, commandLine.getOptionValue("post", "postprocessor.json"))
    val regex = "([#;])? *(https://(?:zh.)?(?:xhamsterlive|stripchat).com/\\S+)(?: (.+))?".toRegex()
    val rooms = channelFlow {
        if (!jobFile.exists())
            jobFile.writeText(BUILTIN.joinToString("\n"))
        for (line in jobFile.bufferedReader().lines().filter { it.isNotBlank() }) {
            send(line)
        }
    }.map { it: String ->
        val match = regex.find(it) ?: return@map null
        val active = match.groupValues[1].isBlank()
        val url = match.groupValues[2]
        val quality = extract(match.groupValues[3], "q:(\\S+)".toRegex(), "720p")
        val timeLimit = extract(match.groupValues[3], "limit:(\\d+)".toRegex(), "0")
        val sizeLimit = extract(match.groupValues[3], "size:(\\S+)".toRegex(), "0")
        val autopay = extract(match.groupValues[3], "(autopay)".toRegex(), "") == "autopay"
        rootLogger.info("loads: ${if (active) "[active]" else "[      ]"} quality:$quality limit:$timeLimit${if (autopay) " autopay " else " "}url:$url")
        async {
            val room = withRetryOrNull(3, { it.message?.contains("404") == true }) {
                API.getRoomFromUrlOrSlug(url, quality)
            } ?: run {
                rootLogger.warn("failed: {}", url)
                return@async null
            }
            if (timeLimit.toLong() > 0) {
                room.timeLimit = timeLimit.toLong().seconds
            }
            room.sizeLimit = Json.decodeFromString(SizeStrSerializer,"\"$sizeLimit\"")
            room.autoPay = autopay
            rootLogger.info("Got new room {}.", room)
            room to active
        }
    }.toList().filterNotNull().awaitAll().filterNotNull().toMutableList()

    File(commandLine.getOptionValue("o", "out")).mkdirs()
    File(commandLine.getOptionValue("t", "tmp")).mkdirs()
    val scheduler =
        Scheduler(
            commandLine.getOptionValue("o", "out"),
            commandLine.getOptionValue("t", "tmp"),

            ) { scheduler ->
            saveJobFile(jobFile, scheduler)
        }
    rooms.forEach {
        File("screenshot/${it.first.name}").mkdir()
        scheduler.add(it.first, it.second)
    }

    val userfile = File(commandLine.getOptionValue("u", "users.txt"))
    if (userfile.exists().not()) {
        userfile.createNewFile()
    }
    for (line in userfile.readLines()) {
        if (line.trim().startsWith(";") || line.trim().startsWith("#"))
            continue
        try {
            val u = API.getUserFromCookie(line.trim())
            rootLogger.info("New user: {}", u)
            UserManager.update(u)
        } catch (e: Exception) {
            rootLogger.warn(
                "Failed to get user from cookie ***${line.subSequence((line.lastIndex - 10).coerceAtLeast(0)..line.lastIndex)}",
                e
            )
        }
    }

    println("-".repeat(10) + "DONE" + "-".repeat(10))

    // web server
    var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    engine = embeddedServer(
        CIO,
        port = commandLine.getOptionValue("p", "8090").toInt(),
        host = "0.0.0.0"

    ) {
        install(ContentNegotiation) {
            json()
        }
        install(CORS) {
            anyHost() // @TODO: Don't do this in production if possible. Try to limit it.
        }
        routing {
            get("/") {
                call.respondText(this::class.java.getResource("/vue.html")!!.readText(), ContentType.Text.Html)
            }
            get("/add") {
                val active = call.request.queryParameters["active"].toBoolean()
                val slug = call.request.queryParameters["slug"]
                val limit = call.request.queryParameters["limit"]?.toLongOrNull() ?: 0L
                val sizeLimit = call.request.queryParameters["size"]?.toLongOrNull() ?: 0L
                if (slug == null) {
                    call.respond(HttpStatusCode.NotAcceptable, "slug not provided.")
                    return@get
                }
                val url = "https://$HOST/${slug.substringAfterLast("/").substringBefore("#")}"
                val q = call.request.queryParameters["quality"] ?: "720p"
                println("${if (active) "[+]" else "[X]"} $q $slug")
                val room = withRetryOrNull(5, { it.message?.contains("404") == true }) {
                    API.getRoomFromUrlOrSlug(url, q)
                }
                if (room == null) {
                    call.respond(HttpStatusCode.InternalServerError, "Failed to get room info.")
                    return@get
                }
                if (scheduler.sessions.keys.any { it.room.name == room.name }) {
                    println("Exist ${room.name}")
                    call.respond(HttpStatusCode.InternalServerError, "Exist ${room.name}.")
                    return@get
                }
                if (limit > 0) {
                    room.timeLimit = limit.seconds
                }
                room.sizeLimit = sizeLimit
                println(room)
                scheduler.add(room, active)
                saveJobFile(jobFile, scheduler)
                call.respond(HttpStatusCode.OK)
            }
            get("/remove") {
                val slug = call.request.queryParameters["slug"]
                if (slug == null) {
                    call.respond(HttpStatusCode.NotAcceptable, "slug not provided.")
                    return@get
                }
                scheduler.remove(slug)
                saveJobFile(jobFile, scheduler)
                call.respond("OK")
            }
            get("/start") {
                scheduler.start(false)
                call.respond(HttpStatusCode.OK, "OK.")

            }
            get("/break") {
                val slug = call.request.queryParameters["slug"]
                if (slug == null) {
                    call.respond(HttpStatusCode.NotAcceptable, "slug not provided.")
                    return@get
                }
                scheduler.cmdFinish(slug, Event.CmdFinish())
                call.respond("OK.")
            }
            get("/restart") {
                val slug = call.request.queryParameters["slug"]
                if (slug == null) {
                    call.respond(HttpStatusCode.NotAcceptable, "slug not provided.")
                    return@get
                }
                scheduler.restartRecorder(slug)
                call.respond("OK.")
            }
            get("/stop") {
                scheduler.job?.cancel()
                scheduler.job?.join()
                call.respond(HttpStatusCode.OK, "OK.")
            }
            get("/activate") {
                val slug = call.request.queryParameters["slug"]
                if (slug == null) {
                    call.respond(HttpStatusCode.NotAcceptable, "slug not provided.")
                    return@get
                }
                scheduler.active(slug)
                saveJobFile(jobFile, scheduler)
                call.respond("OK")
            }
            get("/quality") {
                val slug = call.request.queryParameters["slug"]
                if (slug == null) {
                    call.respond(HttpStatusCode.NotAcceptable, "slug not provided.")
                    return@get
                }
                val q = call.request.queryParameters["quality"]
                if (q == null) {
                    call.respond(HttpStatusCode.NotAcceptable, "quality not provided.")
                    return@get
                }
                val room = scheduler.sessions.keys.find { it.room.name.equals(slug, true) }?.room
                if (room == null) {
                    call.respond(HttpStatusCode.NotAcceptable, "Room $slug not found.")
                    return@get
                }
                room.quality = q
                saveJobFile(jobFile, scheduler)
                call.respond(room)
            }
            get("/autopay") {
                val slug = call.request.queryParameters["slug"]
                if (slug == null) {
                    call.respond(HttpStatusCode.NotAcceptable, "slug not provided.")
                    return@get
                }
                val autopay = call.request.queryParameters["value"]?.toBoolean()
                if (autopay == null) {
                    call.respond(HttpStatusCode.NotAcceptable, "value not provided.")
                    return@get
                }
                val room = scheduler.sessions.keys.find { it.room.name.equals(slug, true) }?.room
                if (room == null) {
                    call.respond(HttpStatusCode.NotAcceptable, "Room $slug not found.")
                    return@get
                }
                room.autoPay = autopay
                saveJobFile(jobFile, scheduler)
                call.respond(room)
            }
            get("/limit") {
                val slug = call.request.queryParameters["slug"]
                if (slug == null) {
                    call.respond(HttpStatusCode.NotAcceptable, "slug not provided.")
                    return@get
                }
                val limit = call.request.queryParameters["limit"]?.toLongOrNull()
                if (limit == null) {
                    call.respond(HttpStatusCode.NotAcceptable, "limit not provided or invalid.")
                    return@get
                }
                val room = scheduler.sessions.keys.find { it.room.name.equals(slug, true) }?.room
                if (room == null) {
                    call.respond(HttpStatusCode.NotAcceptable, "Room $slug not found.")
                    return@get
                }
                room.timeLimit = if (limit == 0L) Duration.INFINITE else limit.seconds
                saveJobFile(jobFile, scheduler)
                call.respond(room)
            }
            get("/sizelimit") {
                val slug = call.request.queryParameters["slug"]
                if (slug == null) {
                    call.respond(HttpStatusCode.NotAcceptable, "slug not provided.")
                    return@get
                }
                val sizelimit = call.request.queryParameters["size"]
                if (sizelimit == null) {
                    call.respond(HttpStatusCode.NotAcceptable, "size not provided.")
                    return@get
                }
                val sizeLong = try {
                    Json.decodeFromString(SizeStrSerializer, JsonPrimitive(sizelimit).toString())
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.NotAcceptable, "size not valid.")
                    throw e
                }
                val room = scheduler.sessions.keys.find { it.room.name.equals(slug, true) }?.room
                if (room == null) {
                    call.respond(HttpStatusCode.NotAcceptable, "Room $slug not found.")
                    return@get
                }
                room.sizeLimit = sizeLong
                saveJobFile(jobFile, scheduler)
                call.respond(room)
            }
            get("/deactivate") {

                val slug = call.request.queryParameters["slug"]
                if (slug == null) {
                    call.respond(HttpStatusCode.NotAcceptable, "slug not provided.")
                    return@get
                }
                scheduler.deactivate(slug)
                saveJobFile(jobFile, scheduler)
                call.respond("OK")
            }
            get("/list") {
                val list = scheduler.sessions.map { (state, session) ->
                    async {
                        listOf(
                            session.status,
                            if (state.listen) "listening" else "         ",
                            if (session.isActive) "recording" else "         ",
                            state.room.name,
                            state.room.id.toString(),
                            state.room.quality,
                            if (state.room.timeLimit.isFinite()) state.room.timeLimit.inWholeSeconds.toString() else "0",
                        )
                    }
                }.awaitAll()
                call.respond(list)
            }
            get("/listv2") {
                val list = scheduler.sessions.map { (state, session) ->
                    async {
                        buildJsonObject {
                            put("session", buildJsonObject {
                                put("status", session.status)
                                put("active", session.isActive)
                            })
                            put("listening", state.listen)
                            put("room", Json.Default.encodeToJsonElement(Room.serializer(), state.room))
                        }
                    }
                }.awaitAll()
                call.respond(list)
            }
            get("/status") {
                call.respond(scheduler.sessions.filter { it.value.isActive }
                    .map { it.key.room.name to it.value.status() }.toMap())
            }
            get("/metrics") {
                call.respond(Metric.prometheus())
            }
            get("/recorders") {
                synchronized(scheduler.sessions) {
                    runBlocking {
                        call.respond(scheduler.sessions.map { it.key.room })
                    }
                }
            }
            get("/graceful-stop") {
                if(scheduler.gracefulStop) {
                    call.respond(HttpStatusCode.NotAcceptable, "Already graceful stopping.")
                    return@get
                }
//                sc.cancel()
                val sessions = scheduler.sessions.filter { it.value.isActive }
                val latch = AtomicInteger(sessions.size)
                val lock = Mutex()
                call.respondTextWriter {
                    lock.withLock {
                        write("Stopping server...\n")
                    }
                    flush()
                    scheduler.gracefulStop=true
                    scheduler.job?.cancelAndJoin()
                    lock.withLock {
                        write("Canceled scheduler.\n")
                    }
                    flush()
                    sessions.map {
                        lock.withLock {
                            write("Waiting ${it.key.room.name}.\n")
                            flush()
                        }
                        scheduler.scope.launch {
                            it.value.join()
                            it.value.stop()
                            lock.withLock {
                                write("Exited ${it.key.room.name}. remain: ${latch.decrementAndGet()}\n")
                            }
                            flush()
                        }
                    }.joinAll()
                    write("OK")
                    flush()
                }
                engine?.stop()
            }
            get("/stop-server") {
//                sc.cancel()
                val sessions = scheduler.sessions.filter { it.value.isActive }
                val latch = AtomicInteger(sessions.size)
                val lock = Mutex()
                call.respondTextWriter {
                    lock.withLock {
                        write("Stopping server...\n")
                    }
                    flush()
                    scheduler.job?.cancel()
                    scheduler.job?.join()
                    lock.withLock {
                        write("Canceled scheduler.\n")
                    }
                    flush()
                    sessions.map {
                        lock.withLock {
                            write("Cancelling ${it.key.room.name}.\n")
                        }
                        flush()
                        scheduler.scope.launch {
                            it.value.stop()
                            lock.withLock {
                                write("Cancelled ${it.key.room.name}. remain: ${latch.decrementAndGet()}\n")
                            }
                            flush()
                        }
                    }.joinAll()
                    write("OK")
                    flush()
                }
                engine?.stop()
            }
        }
    }
    rootLogger.info("start event dispatcher")
    val jobEventDispatcher = launch { EventDispatcher.run() }
    rootLogger.info("start scheduler")
    val jobScheduler = launch { scheduler.start(true) }
    rootLogger.info("start server")
    engine.start(true)
    rootLogger.info("Server Stopped, waiting post processors exit...")
    jobEventDispatcher.cancel()
    withContext(NonCancellable) {
        scheduler.stop()
    }
    // Suppress waring
    rootLogger.info("all done")
    ClientManager.close()
    return@runBlocking
}

@OptIn(InternalCoroutinesApi::class)
private fun saveJobFile(jobFile: File, scheduler: Scheduler) {
    synchronized(jobFile) {
        jobFile.writeText(scheduler.sessions.keys.joinToString("\n") {
            "${if (it.listen) "" else "#"}https://$HOST/${it.room.name} q:${it.room.quality} " +
                    (if (it.room.timeLimit.isFinite()) " limit:${it.room.timeLimit.inWholeSeconds} " else " ") +
                    (if (it.room.sizeLimit!=0L) " size:${Json.encodeToString(SizeStrSerializer,it.room.sizeLimit).removeSurrounding("\"")} " else " ") +
                    (if (it.room.autoPay) " autopay " else " ")
        })
    }
}

