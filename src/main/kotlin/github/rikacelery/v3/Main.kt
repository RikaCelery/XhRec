package github.rikacelery.v3

import github.rikacelery.v3.api.ApiClient
import github.rikacelery.v3.bootstrap.Bootstrap
import github.rikacelery.v3.components.*
import github.rikacelery.v3.core.DataChannel
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.data.SystemConfig
import github.rikacelery.v3.utils.SensitiveStringRegistry
import github.rikacelery.v3.hooks.EventHook
import github.rikacelery.v3.m3u8.M3u8Parser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.cli.DefaultParser
import org.apache.commons.cli.Options
import org.apache.commons.cli.ParseException
import org.apache.commons.cli.help.HelpFormatter
import java.io.File

private data class PersistedConfig(
    val pkey: String,
    val decryptKeys: Map<String, String>,
    val maskSensitiveLogs: Boolean
)

private fun loadPersistedConfig(configPath: String): PersistedConfig {
    val file = File(configPath)
    val key = "YzWScuyQRGAGcxx1KIJmiQ7BY9Vi35ftwLqUOVO8uoo="
    val pkey = "Fq6m2TO2ZeBkRPm9"
    val default = PersistedConfig(pkey, mapOf(pkey to key), true)
    if (!file.exists()) return default
    try {
        val json = Json.parseToJsonElement(file.readText()).jsonObject
        val pkey = json["streamAuthKey"]?.jsonPrimitive?.content ?: pkey
        val keys = mutableMapOf(pkey to key)
        json["decryptKeys"]?.jsonObject?.forEach { (k, v) -> keys[k] = v.jsonPrimitive.content }
        val mask = json["maskSensitiveLogs"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
        return PersistedConfig(pkey, keys, mask)
    } catch (_: Exception) {
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
    } catch (_: ParseException) {
        val formatter = HelpFormatter.builder().get()
        formatter.printOptions(cliOptions)
        return
    }

    runBlocking {
        val appScope = this

        val configPath = "xhrec.json"
        val persisted = loadPersistedConfig(configPath)

        val config = SystemConfig(
            outputDir = File(cli.getOptionValue("output", "out")),
            tmpDir = File(cli.getOptionValue("tmp", "tmp")),
            port = cli.getOptionValue("port", "8090").toInt(),
            proxy = System.getenv("http_proxy"),
            decryptKeys = persisted.decryptKeys,
            streamAuthKey = persisted.pkey,
            authToken = "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJzdWIiOiItMTA4MSIsImluZm8iOnsiaXNHdWVzdCI6dHJ1ZSwidXNlcklkIjotMTA4MX19.IXF36-UfCEmOPGvhl2a19rgLsh2rDCdXNJ3su9LkA9Y",
            platformHost = "stripchat.com",
            listConfPath = cli.getOptionValue("file", "list.conf"),
            configPath = configPath,
            maskSensitiveLogs = persisted.maskSensitiveLogs
        )

        // Apply mask config from persisted config
        SensitiveStringRegistry.enabled = persisted.maskSensitiveLogs

        // 1. Core infrastructure
        val eventBus = EventBus()
        val requestBus = RequestBus(eventBus, appScope)
        val dataChannel = DataChannel()
        val mseStore = MseStore()
        dataChannel.installHook(mseStore)
        eventBus.installHook(object : EventHook {
            override suspend fun intercept(event: Any): Any {

                return event
            }
        })


        // 2. Components
        val metricComponent = MetricComponent(eventBus, appScope)
        val configComponent = ConfigComponent(config, eventBus, appScope)
        val authComponent = AuthComponent(cli.getOptionValue("users", "users.txt"), eventBus, appScope)
        val roomComponent =
            RoomComponent(ApiClient, config.listConfPath, config.platformHost, requestBus, eventBus, appScope)
        val liveEventSource = LiveEventSource(config.authToken, eventBus, appScope)

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
        val schedulerComponent = SchedulerComponent(requestBus, sessionComponent, eventBus, appScope)

        val httpServer = HttpServerComponent(
            config.port,
            eventBus,
            requestBus,
            metricComponent,
            postProcessorComponent,
            appScope,
            mseStore
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
            dataChannel.close()
            println("XhRec v3 shut down")
        }
    }
}
