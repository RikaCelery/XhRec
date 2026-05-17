package github.rikacelery.v3

import github.rikacelery.v3.api.ApiClient
import github.rikacelery.v3.bootstrap.Bootstrap
import github.rikacelery.v3.components.*
import github.rikacelery.v3.core.*
import github.rikacelery.v3.data.SystemConfig
import github.rikacelery.v3.m3u8.M3u8Parser
import kotlinx.coroutines.*
import java.io.File

fun main(vararg args: String): Unit = runBlocking {
    coroutineScope {
        val appScope = this

        val config = SystemConfig(
            outputDir = File("out"),
            tmpDir = File("tmp"),
            port = 8090,
            proxy = System.getenv("http_proxy"),
            decryptKeys = mapOf("v2" to "YzWScuyQRGAGcxx1KIJmiQ7BY9Vi35ftwLqUOVO8uoo="),
            authToken = "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJzdWIiOiItMTA4MSIsImluZm8iOnsiaXNHdWVzdCI6dHJ1ZSwidXNlcklkIjotMTA4MX19.IXF36-UfCEmOPGvhl2a19rgLsh2rDCdXNJ3su9LkA9Y",
            platformHost = "stripchat.com"
        )

        // 1. Core infrastructure
        val eventBus = EventBus()
        val requestBus = RequestBus(eventBus, appScope)
        val dataChannel = DataChannel()

        // 2. Components
        val metricComponent = MetricComponent(eventBus, appScope)
        val configComponent = ConfigComponent(config, eventBus, appScope)
        val authComponent = AuthComponent(eventBus, appScope)
        val roomComponent = RoomComponent(ApiClient, eventBus, appScope)
        val liveEventSource = LiveEventSource(config.authToken, eventBus, appScope)

        val downloaderComponent = DownloaderComponent(
            dataChannel, eventBus = eventBus, parentScope = appScope
        )
        val writerComponent = WriterComponent(
            dataChannel, config.outputDir, config.tmpDir,
            eventBus = eventBus, parentScope = appScope
        )
        val postProcessorComponent = PostProcessorComponent(eventBus = eventBus, parentScope = appScope)
        val sessionComponent = SessionComponent(dataChannel, downloaderComponent, M3u8Parser, requestBus, ApiClient, eventBus, appScope)
        val schedulerComponent = SchedulerComponent(requestBus, sessionComponent, eventBus, appScope)

        val httpServer = HttpServerComponent(config.port, eventBus, requestBus, metricComponent, appScope)

        // 3. Bootstrap: load users, processors, rooms from config files
        val bootstrap = Bootstrap(ApiClient, roomComponent, authComponent, postProcessorComponent,schedulerComponent)
        bootstrap.initialize(args.toList())

        // 4. Start all Actors
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

        // 5. Start HTTP server
        val engine = httpServer.start()

        // 6. Wait for shutdown
        eventBus.subscribe(appScope, String::class) { msg ->
            if (msg == "ServerShutdown") {
                engine.stop(1000, 5000)
                appScope.cancel()
            }
        }

        // 7. Cleanup on exit
        try {
            awaitCancellation()
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
