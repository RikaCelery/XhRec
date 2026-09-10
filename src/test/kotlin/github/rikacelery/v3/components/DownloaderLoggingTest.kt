package github.rikacelery.v3.components

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import github.rikacelery.v3.core.DataChannel
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.data.RuntimeTuning
import github.rikacelery.v3.events.Download
import github.rikacelery.v3.events.DownloadError
import github.rikacelery.v3.events.Segment
import github.rikacelery.v3.events.SegmentDownloaded
import github.rikacelery.v3.hooks.EventHook
import github.rikacelery.v3.utils.CdnSelector
import github.rikacelery.v3.utils.HttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DownloaderLoggingTest {
    @Test
    fun `stall recovered by proxy only logs at trace`() = checkLogging(Scenario.PROXY_RECOVERY)

    @Test
    fun `HTTP error recovered by proxy only logs at trace`() = checkLogging(Scenario.HTTP_RECOVERY)

    @Test
    fun `stall recovered by another CDN only logs at trace`() = checkLogging(Scenario.CDN_RECOVERY)

    @Test
    fun `final failure logs both routes and previous CDN stalls once`() = checkLogging(Scenario.CDN_FAILURE)

    @Test
    fun `exhausted deadline logs attempt timeout history once`() = checkLogging(Scenario.DEADLINE)

    private fun checkLogging(scenario: Scenario) = runBlocking {
        val firstHost = AtomicReference<String>()
        fun client(proxied: Boolean) = HttpClient(MockEngine { request ->
            if (request.method == HttpMethod.Head) return@MockEngine respond("")
            firstHost.compareAndSet(null, request.url.host)
            when {
                scenario == Scenario.DEADLINE -> delay(2.seconds)
                scenario == Scenario.HTTP_RECOVERY && !proxied ->
                    return@MockEngine respond("unavailable", HttpStatusCode.ServiceUnavailable)
                scenario == Scenario.PROXY_RECOVERY && !proxied -> delay(2.seconds)
                scenario in listOf(Scenario.CDN_RECOVERY, Scenario.CDN_FAILURE) -> {
                    if (request.url.host == firstHost.get()) delay(2.seconds)
                    else if (scenario == Scenario.CDN_FAILURE) {
                        return@MockEngine respond("gone", HttpStatusCode.NotFound)
                    }
                }
            }
            respond("segment")
        }) { expectSuccess = true }

        val direct = client(false)
        val proxy = client(true)
        val provider = object : HttpClientProvider {
            override fun direct(key: String, http1: Boolean, expectSuccess: Boolean) = direct
            override fun proxied(key: String, http1: Boolean, expectSuccess: Boolean) = proxy
        }
        val bus = EventBus()
        val terminal = CompletableDeferred<Any>()
        bus.installHook(object : EventHook {
            override suspend fun intercept(event: Any): Any {
                if (event is DownloadError || event is SegmentDownloaded) terminal.complete(event)
                return event
            }
        })
        val tuning = RuntimeTuning(
            downloaderStallTimeout = if (scenario == Scenario.DEADLINE) 1.seconds else 50.milliseconds,
            downloaderRaceDelay = 500.milliseconds,
            downloaderAttemptTimeout = if (scenario == Scenario.DEADLINE) 30.milliseconds else 1.seconds,
            downloaderDeadline = if (scenario == Scenario.DEADLINE) 150.milliseconds else 5.seconds,
            downloaderRetryBackoff = 1.milliseconds
        )
        val downloader = DownloaderComponent(
            DataChannel(), eventBus = bus, parentScope = this,
            httpClientProvider = provider, runtimeTuning = tuning
        )
        val logger = LoggerFactory.getLogger("DownloaderComponent") as Logger
        val oldLevel = logger.level
        val oldAdditive = logger.isAdditive
        val logs = CopyOnWriteArrayList<ILoggingEvent>()
        val appender = object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                event.prepareForDeferredProcessing()
                logs += event
            }
        }
        val oldHosts = CdnSelector.hosts
        CdnSelector.updateHosts(emptyList())
        CdnSelector.updateHosts(listOf("cdn-a.test", "cdn-b.test"))
        appender.start()
        logger.addAppender(appender)
        logger.level = Level.TRACE
        logger.isAdditive = false
        try {
            downloader.handle(DoDownload(Download(11, listOf(Segment("http://cdn-a.test/segment.mp4", 0)), 0, 1)))
            val result = withTimeout(10.seconds) { terminal.await() }
            val visible = logs.filter { it.level.isGreaterOrEqual(Level.DEBUG) }
            if (scenario == Scenario.CDN_FAILURE || scenario == Scenario.DEADLINE) {
                assertIs<DownloadError>(result)
                assertEquals(1, visible.size, visible.joinToString { it.formattedMessage })
                val warning = visible.single()
                assertEquals(Level.WARN, warning.level)
                val message = warning.formattedMessage
                assertTrue(message.startsWith("Segment download failed:"), message)
                if (scenario == Scenario.CDN_FAILURE) {
                    assertTrue("attempts=2, stalls=2" in message, message)
                    assertTrue("${firstHost.get()} DIRECT: stall:" in message, message)
                    assertTrue("${firstHost.get()} PROXY: stall:" in message, message)
                    val secondHost = if (firstHost.get() == "cdn-a.test") "cdn-b.test" else "cdn-a.test"
                    assertTrue("$secondHost DIRECT: HTTP 404" in message, message)
                } else {
                    assertTrue("ATTEMPT: attempt timeout" in message, message)
                }
            } else {
                assertIs<SegmentDownloaded>(result)
                assertTrue(visible.isEmpty(), visible.joinToString { it.formattedMessage })
            }
            if (scenario != Scenario.DEADLINE) {
                assertTrue(logs.any { it.level == Level.TRACE && "downloadWithClient" in it.formattedMessage })
                assertTrue(logs.any { it.level == Level.TRACE && "Direct download slow/failed for" in it.formattedMessage })
            }
        } finally {
            downloader.stop()
            direct.close()
            proxy.close()
            logger.detachAppender(appender)
            appender.stop()
            logger.level = oldLevel
            logger.isAdditive = oldAdditive
            CdnSelector.updateHosts(oldHosts)
        }
    }

    private enum class Scenario { PROXY_RECOVERY, HTTP_RECOVERY, CDN_RECOVERY, CDN_FAILURE, DEADLINE }
}
