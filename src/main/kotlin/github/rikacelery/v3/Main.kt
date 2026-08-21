package github.rikacelery.v3

import github.rikacelery.v3.api.ApiClient
import github.rikacelery.v3.bootstrap.Bootstrap
import github.rikacelery.v3.components.*
import github.rikacelery.v3.core.DataChannel
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.data.Hosts
import github.rikacelery.v3.data.HostsConfig
import github.rikacelery.v3.data.SystemConfig
import github.rikacelery.v3.hooks.EventHook
import github.rikacelery.v3.m3u8.M3u8Parser
import github.rikacelery.v3.utils.CdnSelector
import github.rikacelery.v3.ml.PredictionEngine
import github.rikacelery.v3.utils.PredictionStore
import github.rikacelery.v3.utils.SensitiveStringRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    val apiToken: String
)

private val mainLogger = LoggerFactory.getLogger("v3.Main")

private fun loadPersistedConfig(configPath: String): PersistedConfig {
    val file = File(configPath)
    val key = "YzWScuyQRGAGcxx1KIJmiQ7BY9Vi35ftwLqUOVO8uoo="
    val pkey = "Fq6m2TO2ZeBkRPm9"
    val default = PersistedConfig(pkey, mapOf(pkey to key), true, HostsConfig.DEFAULT, "")
    if (!file.exists()) return default
    try {
        val json = Json.parseToJsonElement(file.readText()).jsonObject
        val pkey = json["streamAuthKey"]?.jsonPrimitive?.content ?: pkey
        val keys = mutableMapOf(pkey to key)
        json["decryptKeys"]?.jsonObject?.forEach { (k, v) -> keys[k] = v.jsonPrimitive.content }
        val mask = json["maskSensitiveLogs"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
        val hosts = HostsConfig.fromJson(json)
        val apiToken = json["apiToken"]?.jsonPrimitive?.content ?: ""
        return PersistedConfig(pkey, keys, mask, hosts, apiToken)
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
            proxy = System.getenv("http_proxy"),
            decryptKeys = persisted.decryptKeys,
            streamAuthKey = persisted.pkey,
            hosts = persisted.hosts,
            listConfPath = cli.getOptionValue("file", "list.conf"),
            configPath = configPath,
            maskSensitiveLogs = persisted.maskSensitiveLogs,
            apiToken = persisted.apiToken
        )

        // Apply persisted runtime config before components start making API calls
        Hosts.current = persisted.hosts
        ApiClient.applyHosts(persisted.hosts.platformHosts)
        CdnSelector.updateHosts(persisted.hosts.hlsHosts)
        SensitiveStringRegistry.enabled = persisted.maskSensitiveLogs

        // 1. Core infrastructure
        val eventBus = EventBus()
        val requestBus = RequestBus(eventBus, appScope)
        val dataChannel = DataChannel()
        val mseStore = MseStore()
        dataChannel.installHook(mseStore)
        eventBus.installHook(object : EventHook {
            override suspend fun intercept(event: Any): Any {
                if (mainLogger.isTraceEnabled){
                    mainLogger.trace("[BUS] {}",event)
                }
                return event
            }
        })


        // 2. Components
        val metricComponent = MetricComponent(eventBus, appScope)
        val configComponent = ConfigComponent(config, eventBus, appScope)
        val authComponent = AuthComponent(cli.getOptionValue("users", "users.txt"), eventBus, appScope)
        val roomComponent =
            RoomComponent(ApiClient, config.listConfPath, requestBus, eventBus, appScope)
        // WS auth JWT is fetched dynamically at startup from config/initial (guest session),
        // refreshed on auth failure or after its (unknown) validity window.
        val liveEventSource = LiveEventSource({ ApiClient.fetchGuestWsToken() }, eventBus, appScope)

        val downloaderComponent = DownloaderComponent(
            dataChannel, eventBus = eventBus, parentScope = appScope, initialConcurrency = 64
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
            ApiClient,
            config.streamAuthKey,
            eventBus,
            appScope
        )
        val schedulerComponent = SchedulerComponent(requestBus, sessionComponent, eventBus, appScope, config.streamAuthKey)

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
            Bootstrap(ApiClient, roomComponent, authComponent, postProcessorComponent, schedulerComponent)
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

        // 7. Cleanup on exit
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
            appScope.cancel()
            println("XhRec v3 shut down")
        }
    }
}
