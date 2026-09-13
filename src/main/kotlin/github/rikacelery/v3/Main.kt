package github.rikacelery.v3

import github.rikacelery.v3.api.ApiClient
import github.rikacelery.v3.bootstrap.Bootstrap
import github.rikacelery.v3.components.*
import github.rikacelery.v3.core.DataChannel
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.data.Hosts
import github.rikacelery.v3.data.HostsConfig
import github.rikacelery.v3.data.RuntimeTuning
import github.rikacelery.v3.data.SystemConfig
import github.rikacelery.v3.events.*
import github.rikacelery.v3.hooks.EventHook
import github.rikacelery.v3.m3u8.M3u8Parser
import github.rikacelery.v3.ml.PredictionEngine
import github.rikacelery.v3.utils.CdnSelector
import github.rikacelery.v3.utils.ClientManager
import github.rikacelery.v3.utils.DefaultHttpClientProvider
import github.rikacelery.v3.utils.LogLevels
import github.rikacelery.v3.utils.PredictionStore
import github.rikacelery.v3.utils.SensitiveStringRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds
import org.apache.commons.cli.DefaultParser
import org.apache.commons.cli.Options
import org.apache.commons.cli.ParseException
import org.apache.commons.cli.help.HelpFormatter
import org.slf4j.LoggerFactory
import java.io.File

private data class PersistedConfig(
    val pkey: String,
    val decryptKeys: Map<String, String>,
    val maskSensitiveLogs: Boolean,
    val hosts: HostsConfig,
    val apiToken: String,
    val logLevel: String
)

private val mainLogger = LoggerFactory.getLogger("v3.Main")

private fun loadPersistedConfig(configPath: String): PersistedConfig {
    val file = File(configPath)
    val key = "YzWScuyQRGAGcxx1KIJmiQ7BY9Vi35ftwLqUOVO8uoo="
    val pkey = "Fq6m2TO2ZeBkRPm9"
    val default = PersistedConfig(pkey, mapOf(pkey to key), true, HostsConfig.DEFAULT, "", "")
    if (!file.exists()) return default
    try {
        val json = Json.parseToJsonElement(file.readText()).jsonObject
        val pkey = json["streamAuthKey"]?.jsonPrimitive?.content ?: pkey
        val keys = mutableMapOf(pkey to key)
        json["decryptKeys"]?.jsonObject?.forEach { (k, v) -> keys[k] = v.jsonPrimitive.content }
        val mask = json["maskSensitiveLogs"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
        val hosts = HostsConfig.fromJson(json)
        val apiToken = json["apiToken"]?.jsonPrimitive?.content ?: ""
        val logLevel = json["logLevel"]?.jsonPrimitive?.content ?: ""
        return PersistedConfig(pkey, keys, mask, hosts, apiToken, logLevel)
    } catch (e: Exception) {
        mainLogger.error("Failed to load persisted config from $configPath", e)
        return default
    }
}

fun main(vararg args: String) {
    val cliOptions = Options()
        .addOption("f", "file", true, "list.conf path")
        .addOption("o", "output", true, "output directory")
        .addOption("t", "tmp", true, "temp directory")
        .addOption("p", "port", true, "HTTP port")
        .addOption("s", "tls", true, "Enable TLS/SSL (Default: true)")
        .addOption("u", "users", true, "users.txt path")
        .addOption("post", true, "postprocessor.json path")
    val cli = try {
        DefaultParser().parse(cliOptions, args.toList().toTypedArray())
    } catch (e: ParseException) {
        mainLogger.error("CLI argument parse error", e)
        val formatter = HelpFormatter.builder().get()
        formatter.printOptions(cliOptions)
        return
    }

    runBlocking {
        // Components run on a shared Default pool: each actor still processes its
        // mailbox serially, but different components can work in parallel. Blocking
        // IO is dispatched explicitly with withContext(Dispatchers.IO) at each site.
        val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineName("xhrec-app"))

        val configPath = "xhrec.json"
        val persisted = loadPersistedConfig(configPath)

        val config = SystemConfig(
            outputDir = File(cli.getOptionValue("output", "out")),
            tmpDir = File(cli.getOptionValue("tmp", "tmp")),
            port = cli.getOptionValue("port", "8090").toInt(),
            tls = cli.getOptionValue("tls", "true").toBoolean(),
            proxy = System.getenv("http_proxy"),
            decryptKeys = persisted.decryptKeys,
            streamAuthKey = persisted.pkey,
            hosts = persisted.hosts,
            listConfPath = cli.getOptionValue("file", "list.conf"),
            configPath = configPath,
            maskSensitiveLogs = persisted.maskSensitiveLogs,
            apiToken = persisted.apiToken,
            logLevel = persisted.logLevel
        )

        // Apply persisted runtime config before components start making API calls
        Hosts.current = persisted.hosts
        val httpClientProvider = DefaultHttpClientProvider
        val runtimeTuning = RuntimeTuning()
        val apiClient = ApiClient(persisted.hosts.platformHosts, httpClientProvider)
        CdnSelector.updateHosts(persisted.hosts.hlsHosts)
        SensitiveStringRegistry.enabled = persisted.maskSensitiveLogs
        // Restore the dashboard's log level before the components start, so their startup logs
        // already honor it. ConfigComponent re-applies it when it loads the file.
        if (persisted.logLevel.isNotBlank() && LogLevels.apply(persisted.logLevel) == null) {
            mainLogger.warn("Ignoring unknown persisted log level '{}'", persisted.logLevel)
        }

        // 1. Core infrastructure
        val eventBus = EventBus()
        val requestBus = RequestBus(eventBus, appScope)
        val dataChannel = DataChannel()
        val mseStore = MseStore()
        dataChannel.installHook(mseStore)
        eventBus.installHook(object : EventHook {
            override suspend fun intercept(event: Any): Any {
                if (mainLogger.isTraceEnabled) {
                    mainLogger.trace("[BUS] {}", event)
                }
                return event
            }
        })


        // 2. Components
        val metricComponent = MetricComponent(eventBus, appScope)
        val configComponent = ConfigComponent(config, apiClient, eventBus, appScope)
        val authComponent = AuthComponent(cli.getOptionValue("users", "users.txt"), eventBus, appScope)
        val roomComponent =
            RoomComponent(apiClient, config.listConfPath, requestBus, eventBus, appScope, runtimeTuning)
        // WS auth JWT is fetched dynamically at startup from config/initial (guest session),
        // refreshed on auth failure or after its (unknown) validity window.
        val liveEventSource = LiveEventSource(
            { apiClient.fetchGuestWsToken() }, eventBus, appScope,
            httpClientProvider = httpClientProvider, runtimeTuning = runtimeTuning
        )

        val downloaderComponent = DownloaderComponent(
            dataChannel, eventBus = eventBus, parentScope = appScope, initialConcurrency = 64,
            httpClientProvider = httpClientProvider, runtimeTuning = runtimeTuning
        )
        val writerComponent = WriterComponent(
            dataChannel, config.tmpDir,
            eventBus = eventBus, parentScope = appScope
        )
        val postProcessorComponent = PostProcessorComponent(eventBus = eventBus, parentScope = appScope)
        val sessionComponent = SessionComponent(
            dataChannel,
            downloaderComponent,
            M3u8Parser,
            requestBus,
            eventBus,
            appScope,
            httpClientProvider,
            runtimeTuning
        )
        val schedulerComponent = SchedulerComponent(
            requestBus,
            sessionComponent,
            apiClient,
            config.streamAuthKey,
            eventBus,
            appScope,
            httpClientProvider,
            runtimeTuning
        )

        // Prediction persistence (loads on init, auto-saves periodically, saves on stop)
        val predictionStore = PredictionStore(
            "xhrec-predictions.json",
            appScope,
            saveIntervalMs = 60_000
        )
        // LightGBM-style GBDT: samples + models in working directory (Docker WORKDIR=/config)
        PredictionEngine.start(appScope, trainIntervalMs = 30 * 60_000L)

        val httpServer = HttpServerComponent(
            config.port,
            config.tls,
            eventBus,
            requestBus,
            metricComponent,
            postProcessorComponent,
            appScope,
            mseStore,
            config.apiToken
        )


        // 3. Start all Actors
        configComponent.start()
        authComponent.start()
        roomComponent.start()
        metricComponent.start()
        liveEventSource.start()
        downloaderComponent.start()
        writerComponent.start()
        postProcessorComponent.start()
        sessionComponent.start()
        schedulerComponent.start()
        predictionStore.start()

        // 4. Bootstrap: load users, processors, rooms from config files
        val bootstrap =
            Bootstrap(apiClient, roomComponent, authComponent, postProcessorComponent, schedulerComponent)
        bootstrap.initialize(args.toList())

        // 5. Start HTTP server
        val engine = httpServer.start()

        // 6. Wait for shutdown
        val shutdownSignal = CompletableDeferred<Unit>()
        eventBus.subscribe(appScope, String::class) { msg ->
            if (msg == "ServerShutdown") {
                engine.stop(1000, 5000)
                shutdownSignal.complete(Unit)
            }
        }

        // 7. JVM shutdown hook: without this, SIGTERM/docker stop kills the
        // process immediately with no chance to run graceful shutdown -
        // in-progress recordings get truncated mid-write and never reach
        // post-processing. This mirrors what the HTTP shutdown routes do, so a
        // container stop behaves the same as a graceful HTTP shutdown.
        // When using docker, specify a longer stop_grace_period, since this can
        // potentially take minutes.
        Runtime.getRuntime().addShutdownHook(Thread {
            runBlocking(Dispatchers.Default) {
                mainLogger.info("Received termination signal, draining sessions before exit...")
                try {
                    requestBus.request<OkResponse>(ShutdownCmd)

                    val sessions = requestBus.request<List<RoomSession>>(GetSessions)
                        .filter { it.state == SessionState.Recording || it.state == SessionState.Fetching }
                    for (s in sessions) {
                        requestBus.request<OkResponse>(DeactivateCmd(s.roomId))
                    }
                    withTimeoutOrNull(120.seconds) {
                        awaitFinished(
                            verb = "Stopped",
                            targets = sessions.associate { it.roomId to it.roomName },
                            stillRunning = {
                                requestBus.request<List<RoomSession>>(GetSessions)
                                    .filter { it.state == SessionState.Recording || it.state == SessionState.Fetching }
                                    .map { it.roomId }
                                    .toSet()
                            }
                        )
                    }

                    withTimeoutOrNull(180.seconds) {
                        awaitFinished(
                            verb = "Post-processed",
                            targets = postProcessorComponent.jobs.keys.associateWith { File(it).name },
                            stillRunning = { postProcessorComponent.jobs.keys.toSet() }
                        )
                    }
                } catch (e: Exception) {
                    mainLogger.error("Error during shutdown drain, exiting anyway: ${e.message}", e)
                } finally {
                    eventBus.publish("ServerShutdown")
                    shutdownSignal.await()
                }
            }
        })

        // 8. Cleanup on exit
        try {
            shutdownSignal.await()
        } finally {
            schedulerComponent.stop()
            sessionComponent.stop()
            downloaderComponent.stop()
            writerComponent.stop()
            postProcessorComponent.stop()
            liveEventSource.stop()
            metricComponent.stop()
            roomComponent.stop()
            authComponent.stop()
            configComponent.stop()
            predictionStore.stop()
            PredictionEngine.stop() // suspend: persist final state before scope is cancelled
            dataChannel.close()
            ClientManager.close()
            appScope.cancel()
            println("XhRec v3 shut down")
        }
    }
}

/**
 * Polls [stillRunning] every 500ms until every key in [targets] has dropped
 * out of the returned set. Used by the shutdown hook to wait for both active
 * recording sessions and pending post-processor jobs.
 */
private suspend fun <K : Any> awaitFinished(
    verb: String,
    targets: Map<K, String>,
    stillRunning: suspend () -> Set<K>
) {
    val done = mutableSetOf<K>()
    while (done.size < targets.size) {
        delay(500)
        val live = stillRunning()
        for ((key, name) in targets) {
            if (key in done || key in live) continue
            done += key
            mainLogger.info("$verb $name. remaining: ${targets.size - done.size}")
        }
    }
}
