package github.rikacelery.v3.integration

import github.rikacelery.v3.api.ApiClient
import github.rikacelery.v3.components.AuthComponent
import github.rikacelery.v3.components.ConfigComponent
import github.rikacelery.v3.components.DownloaderComponent
import github.rikacelery.v3.components.HttpServerComponent
import github.rikacelery.v3.components.LiveEventSource
import github.rikacelery.v3.components.LoadUsers
import github.rikacelery.v3.components.MetricComponent
import github.rikacelery.v3.components.PostProcessorComponent
import github.rikacelery.v3.components.RoomComponent
import github.rikacelery.v3.components.RoomSession
import github.rikacelery.v3.components.SchedulerComponent
import github.rikacelery.v3.components.SessionComponent
import github.rikacelery.v3.components.SessionState
import github.rikacelery.v3.components.WriterComponent
import github.rikacelery.v3.core.DataChannel
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.data.Hosts
import github.rikacelery.v3.data.HostsConfig
import github.rikacelery.v3.data.Room
import github.rikacelery.v3.data.RuntimeTuning
import github.rikacelery.v3.data.SystemConfig
import github.rikacelery.v3.data.User
import github.rikacelery.v3.events.FileReady
import github.rikacelery.v3.events.CommandAck
import github.rikacelery.v3.events.CommandEnvelope
import github.rikacelery.v3.events.GetArmedRoomIds
import github.rikacelery.v3.events.GetRooms
import github.rikacelery.v3.events.GetSessions
import github.rikacelery.v3.events.SegmentDownloaded
import github.rikacelery.v3.events.RoomAdded
import github.rikacelery.v3.hooks.EventHook
import github.rikacelery.v3.m3u8.M3u8Parser
import github.rikacelery.v3.utils.CdnSelector
import github.rikacelery.v3.utils.HttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import io.ktor.http.Parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Runs the real XhRec component graph in-process against one loopback [MockPlatformServer].
 *
 * Only the network boundary and the clock are replaced: clients go straight to the mock
 * (no proxy, no CDN rewriting), timings are compressed, and the writer keeps every byte.
 * Everything else — Room/Scheduler/Session/Downloader/Writer actors, the FSM matrices,
 * and the production HTTP routes — is the shipping implementation.
 */
class XhrecIntegrationFixture(
    private val app: ApplicationTestBuilder,
    val mock: MockPlatformServer = MockPlatformServer(),
    val tuning: RuntimeTuning = testTuning()
) : AutoCloseable {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val eventBus = EventBus()
    val dataChannel = DataChannel(capacity = 4096)
    val requestBus = RequestBus(eventBus, scope, defaultTimeoutMs = 15_000)
    val provider = TestHttpClientProvider()
    val tempRoot: Path = Files.createTempDirectory("xhrec-it")
    val outputDir: File = tempRoot.resolve("out").toFile().apply { mkdirs() }
    val tmpDir: File = tempRoot.resolve("tmp").toFile().apply { mkdirs() }
    val listConfPath: String = tempRoot.resolve("list.conf").toString()
    val usersPath: String = tempRoot.resolve("users.txt").toString()
    val configPath: String = tempRoot.resolve("xhrec.json").toString()

    val events = CopyOnWriteArrayList<Any>()
    private val previousHosts = Hosts.current
    private val previousCdnHosts = CdnSelector.hosts

    val apiClient = ApiClient(listOf(mock.host), provider) { host -> "http://$host" }

    lateinit var config: SystemConfig
        private set
    lateinit var configComponent: ConfigComponent
        private set
    lateinit var authComponent: AuthComponent
        private set
    lateinit var roomComponent: RoomComponent
        private set
    lateinit var liveEventSource: LiveEventSource
        private set
    lateinit var downloaderComponent: DownloaderComponent
        private set
    lateinit var writerComponent: WriterComponent
        private set
    lateinit var sessionComponent: SessionComponent
        private set
    lateinit var schedulerComponent: SchedulerComponent
        private set
    lateinit var metricComponent: MetricComponent
        private set
    lateinit var postProcessorComponent: PostProcessorComponent
        private set
    lateinit var httpServer: HttpServerComponent
        private set

    /** Master playlist candidates pointing at the mock, with the production query shape. */
    val masterUrlCandidates: (Long, String, String?) -> List<String> = { roomId, pkey, token ->
        val url = buildString {
            append(mock.masterUrl(roomId))
            append("?psch=v2&pkey=").append(pkey)
            token?.takeIf { it.isNotEmpty() }?.let { append("&aclAuth=").append(it) }
        }
        listOf(url)
    }

    fun start() {
        eventBus.installHook(object : EventHook {
            override suspend fun intercept(event: Any): Any? {
                events += event
                return event
            }
        })

        Hosts.current = HostsConfig(
            platformHosts = listOf(mock.host),
            webSocketHosts = listOf(mock.host),
            hlsHosts = emptyList(),
            hlsMasterHost = mock.host,
            webHost = mock.host,
            previewHost = mock.host,
            thumbHost = mock.host
        )
        CdnSelector.updateHosts(emptyList())

        config = SystemConfig(
            outputDir = outputDir,
            tmpDir = tmpDir,
            port = 0,
            proxy = null,
            decryptKeys = mapOf(MockPlatformServer.PSCH_KEY to mock.decryptKey),
            streamAuthKey = MockPlatformServer.PSCH_KEY,
            hosts = Hosts.current,
            listConfPath = listConfPath,
            configPath = configPath,
            maskSensitiveLogs = false,
            apiToken = ""
        )

        configComponent = ConfigComponent(config, apiClient, eventBus, scope)
        authComponent = AuthComponent(usersPath, eventBus, scope)
        roomComponent = RoomComponent(apiClient, listConfPath, requestBus, eventBus, scope, tuning)
        liveEventSource = LiveEventSource(
            tokenProvider = { apiClient.fetchGuestWsToken() },
            eventBus = eventBus,
            parentScope = scope,
            wsPoolCount = 1,
            httpClientProvider = provider,
            runtimeTuning = tuning,
            wsUrlBuilder = { host -> "ws://$host/connection/websocket" }
        )
        downloaderComponent = DownloaderComponent(
            dataChannel,
            eventBus = eventBus,
            parentScope = scope,
            initialConcurrency = 4,
            httpClientProvider = provider,
            runtimeTuning = tuning
        )
        writerComponent = WriterComponent(
            dataChannel,
            tmpDir,
            emptyList(),
            minOutputBytes = 0,
            eventBus = eventBus,
            parentScope = scope
        )
        sessionComponent = SessionComponent(
            dataChannel,
            downloaderComponent,
            M3u8Parser,
            requestBus,
            eventBus,
            scope,
            provider,
            tuning
        )
        schedulerComponent = SchedulerComponent(
            requestBus,
            sessionComponent,
            apiClient,
            config.streamAuthKey,
            eventBus,
            scope,
            provider,
            tuning,
            masterUrlCandidates
        )
        metricComponent = MetricComponent(eventBus, scope)
        postProcessorComponent = PostProcessorComponent(eventBus, scope)
        httpServer = HttpServerComponent(
            port = 0,
            eventBus = eventBus,
            requestBus = requestBus,
            metricComponent = metricComponent,
            postProcessorComponent = postProcessorComponent,
            scope = scope,
            runtimeTuning = tuning
        )

        configComponent.start()
        authComponent.start()
        metricComponent.start()
        postProcessorComponent.start()
        roomComponent.start()
        liveEventSource.start()
        downloaderComponent.start()
        writerComponent.start()
        sessionComponent.start()
        schedulerComponent.start()
    }

    /** Marks the room list ready and loads one payable account for group/private flows. */
    suspend fun ready(users: List<User> = listOf(User("cookie-1", 1L, "payer", 100_000L))) {
        roomComponent.setReady()
        authComponent.tell(LoadUsers(users))
    }

    /**
     * Runs the production bootstrap against this fixture's list.conf/users.txt, exactly as
     * `Main` does: rooms are added, armed lines are handed to the scheduler.
     */
    suspend fun bootstrap(vararg extraArgs: String) {
        github.rikacelery.v3.bootstrap.Bootstrap(
            apiClient,
            roomComponent,
            authComponent,
            postProcessorComponent,
            schedulerComponent
        ).initialize(listOf("-f", listConfPath, "-u", usersPath) + extraArgs)
    }

    /**
     * Waits until LiveEventSource has subscribed the room's status channels on the mock.
     *
     * `EventBus.subscribe` launches collectors asynchronously, so a room announced right
     * after startup can miss its `RoomAdded`. Re-announcing is idempotent (subscribeRoom
     * dedupes) and makes the WebSocket path deterministic instead of timing-dependent.
     */
    suspend fun awaitRoomSubscribed(roomId: Long, timeout: Duration = 10.seconds) {
        val channel = "broadcastChanged@$roomId"
        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (System.currentTimeMillis() < deadline) {
            if (mock.subscribedChannels().contains(channel)) return
            rooms().firstOrNull { it.id == roomId }?.let { eventBus.publish(RoomAdded(it.id, it.name)) }
            delay(50.milliseconds)
        }
        throw AssertionError(
            "timed out waiting for $channel; mock subscriptions=${mock.subscribedChannels()}; " +
                "wsConnections=${mock.connectionCount()}"
        )
    }

    /** Installs the production control routes into the Ktor test application. */
    fun installRoutes() {
        app.application { httpServer.installApplication(this, stopEngine = {}) }
    }

    suspend fun get(path: String): HttpResponse = app.client.get(path)

    /** Posts a form body through the production routes, the way the dashboard does. */
    suspend fun postForm(path: String, params: Map<String, String>): HttpResponse =
        app.client.post(path) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(Parameters.build { params.forEach { (key, value) -> append(key, value) } }.formUrlEncode())
        }

    suspend fun dashboard(): JsonObject =
        Json.parseToJsonElement(app.client.get("/dashboard").bodyAsText()).jsonObject

    suspend fun rooms(): List<Room> = requestBus.request(GetRooms)

    suspend fun sessions(): List<RoomSession> = requestBus.request(GetSessions)

    suspend fun awaitRoom(
        name: String,
        timeout: Duration = 10.seconds,
        predicate: (JsonObject) -> Boolean = { true }
    ): JsonObject = await(timeout, "room $name") {
        dashboard()["listv2"]?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { it.path("room.name") == name }
            ?.takeIf(predicate)
    }

    suspend fun awaitRoomAbsent(id: Long, timeout: Duration = 10.seconds) {
        await(timeout, "room $id absent") {
            if (rooms().none { it.id == id }) Unit else null
        }
    }

    suspend fun awaitSession(
        roomId: Long,
        state: SessionState,
        quality: String? = null,
        timeout: Duration = 15.seconds
    ) {
        await(timeout, "session $roomId in $state${quality?.let { " @$it" } ?: ""}") {
            sessions().firstOrNull {
                it.roomId == roomId && it.state == state && (quality == null || it.quality == quality)
            }
        }
    }

    /** Waits until [count] matching events have been observed (useful for repeated FileReady). */
    suspend inline fun <reified T : Any> awaitEventCount(
        count: Int,
        timeout: Duration = 15.seconds,
        noinline predicate: (T) -> Boolean = { true }
    ): List<T> = await(timeout, "$count × ${T::class.simpleName}") {
        events.filterIsInstance<T>().filter(predicate).takeIf { it.size >= count }
    }

    /** Waits until list.conf exists and satisfies [predicate]. */
    suspend fun awaitListConf(
        timeout: Duration = 10.seconds,
        predicate: (String) -> Boolean = { true }
    ): String = await(timeout, "list.conf") {
        val file = File(listConfPath)
        file.takeIf { it.exists() }?.readText()?.takeIf(predicate)
    }

    suspend inline fun <reified T : Any> awaitEvent(
        timeout: Duration = 10.seconds,
        noinline predicate: (T) -> Boolean = { true }
    ): T = await(timeout, "event ${T::class.simpleName}") {
        events.filterIsInstance<T>().firstOrNull(predicate)
    }

    suspend fun awaitFile(roomId: Long, timeout: Duration = 15.seconds): File =
        awaitEvent<FileReady>(timeout) { it.roomId == roomId }.file

    suspend fun <T : Any> await(timeout: Duration, what: String, probe: suspend () -> T?): T {
        val found = withTimeoutOrNull(timeout) {
            var value: T? = null
            while (value == null) {
                value = probe()
                if (value == null) delay(10.milliseconds)
            }
            value
        }
        return found ?: throw AssertionError(
            "timed out after $timeout waiting for $what; " +
                "recent events=${events.filterNot { it is CommandAck || it is CommandEnvelope }.takeLast(25)}; " +
                "recent requests=${mock.requests().takeLast(15).map { "${it.method} ${it.path}" }}; " +
                "sessions=${runCatching { sessions() }.getOrNull()}; " +
                "armed=${runCatching { requestBus.request<List<Long>>(GetArmedRoomIds) }.getOrNull()}"
        )
    }

    override fun close() {
        runCatching { schedulerComponent.stop() }
        runCatching { sessionComponent.stop() }
        runCatching { downloaderComponent.stop() }
        runCatching { writerComponent.stop() }
        runCatching { liveEventSource.stop() }
        runCatching { roomComponent.stop() }
        runCatching { metricComponent.stop() }
        runCatching { postProcessorComponent.stop() }
        runCatching { configComponent.stop() }
        runCatching { authComponent.stop() }
        scope.cancel()
        dataChannel.close()
        provider.close()
        Hosts.current = previousHosts
        CdnSelector.updateHosts(previousCdnHosts)
        runCatching { mock.close() }
        runCatching { tempRoot.toFile().deleteRecursively() }
    }

    companion object {
        /** Compressed production timings: same code paths, seconds instead of minutes. */
        fun testTuning(
            roomPollInterval: Duration = 200.milliseconds,
            playlistPollInterval: Duration = 40.milliseconds,
            downloaderDeadline: Duration = 5.seconds,
            downloaderRaceDelay: Duration = 150.milliseconds,
            downloaderStallTimeout: Duration = 1.seconds
        ) = RuntimeTuning(
            roomPollInterval = roomPollInterval,
            roomRefreshDebounce = 20.milliseconds,
            webSocketReconnectInitial = 50.milliseconds,
            webSocketReconnectMax = 200.milliseconds,
            preconfigRetryInterval = 100.milliseconds,
            playlistPollInterval = playlistPollInterval,
            playlistFetchTimeout = 3.seconds,
            httpRestartDelay = 10.milliseconds,
            downloaderRaceDelay = downloaderRaceDelay,
            downloaderAttemptTimeout = 2.seconds,
            downloaderDeadline = downloaderDeadline,
            downloaderStallTimeout = downloaderStallTimeout,
            downloaderRetryBackoff = 20.milliseconds
        )
    }
}

private fun JsonObject.jsonPath(path: String): String? {
    var element: kotlinx.serialization.json.JsonElement = this
    for (part in path.split('.')) {
        element = element.jsonObject[part] ?: return null
    }
    return element.jsonPrimitive.content
}

/**
 * Test network boundary: loopback clients with the production-relevant plugins
 * (JSON bodies + WebSockets) but no proxy, no retry plugin and no CDN rewriting.
 */
class TestHttpClientProvider : HttpClientProvider, AutoCloseable {
    private fun client(expectSuccess: Boolean): HttpClient = HttpClient(OkHttp) {
        this.expectSuccess = expectSuccess
        install(ContentNegotiation) { json() }
        install(WebSockets)
    }

    private val lenient = client(expectSuccess = false)
    private val strict = client(expectSuccess = true)

    override fun direct(key: String, http1: Boolean, expectSuccess: Boolean): HttpClient =
        if (expectSuccess) strict else lenient

    override fun proxied(key: String, http1: Boolean, expectSuccess: Boolean): HttpClient =
        if (expectSuccess) strict else lenient

    override fun close() {
        lenient.close()
        strict.close()
    }
}

internal fun withFixture(
    tuning: RuntimeTuning = XhrecIntegrationFixture.testTuning(),
    block: suspend (XhrecIntegrationFixture) -> Unit
) = testApplication {
    XhrecIntegrationFixture(this, tuning = tuning).use { fx ->
        fx.start()
        fx.installRoutes()
        block(fx)
    }
}

/** Adds an inactive room, activates it, publishes public status and waits for recording. */
internal suspend fun XhrecIntegrationFixture.startRecording(
    roomId: Long = 1001L,
    name: String = "model",
    status: String = "public",
    segmentPeriod: Duration = 30.milliseconds
) {
    mock.addRoom(roomId, name, status = status)
    get("/add?name=$name&active=false").expectOk("Room added: $name")
    awaitRoom(name) { !it.boolField("listening") }
    get("/activate?id=$roomId").expectOk("Activated")
    mock.startSegments(roomId, segmentPeriod)
    awaitRoomSubscribed(roomId)
    mock.setRoomStatus(roomId, status)
    awaitSession(roomId, SessionState.Recording)
    awaitEvent<SegmentDownloaded>(10.seconds) { it.roomId == roomId }
}

internal fun JsonObject.boolField(field: String): Boolean =
    this[field]?.jsonPrimitive?.content?.toBoolean() ?: false
