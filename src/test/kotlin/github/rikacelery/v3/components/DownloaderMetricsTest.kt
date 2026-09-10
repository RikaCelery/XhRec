package github.rikacelery.v3.components

import github.rikacelery.v3.core.DataChannel
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.data.DownloadResult
import github.rikacelery.v3.events.*
import github.rikacelery.v3.hooks.DownloaderHook
import github.rikacelery.v3.hooks.EventHook
import github.rikacelery.v3.utils.CdnSelector
import github.rikacelery.v3.utils.HttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DownloaderMetricsTest {
    @Test
    fun `permanent HTTP failure is counted once`() = runTest {
        checkDownload(status = HttpStatusCode.NotFound, expectedFailure = "HTTP 404")
    }

    @Test
    fun `worker exception records failure and clears running URL`() = runTest {
        checkDownload(hook = object : PassThroughHook() {
            override suspend fun onDownloadResult(roomId: Long, result: DownloadResult): DownloadResult =
                error("result hook failed")
        }, expectedFailure = "result hook failed")
    }

    @Test
    fun `cancelled worker records failure and clears running URL`() = runTest {
        checkDownload(hook = object : PassThroughHook() {
            override suspend fun onDownloadResult(roomId: Long, result: DownloadResult): DownloadResult =
                throw CancellationException("cancelled by hook")
        }, expectedFailure = "cancelled")
    }

    @Test
    fun `before download exception records failure`() = runTest {
        checkDownload(hook = object : PassThroughHook() {
            override suspend fun beforeDownload(url: String): String = error("before hook failed")
        }, expectedFailure = "before hook failed")
    }

    @Test
    fun `hook rejection is counted using final result`() = runTest {
        checkDownload(hook = object : PassThroughHook() {
            override suspend fun onDownloadResult(roomId: Long, result: DownloadResult): DownloadResult =
                DownloadResult.Failed(0, URL, "rejected by hook")
        }, expectedFailure = "rejected by hook")
    }

    @Test
    fun `rewritten URL is cleared on failure`() = runTest {
        checkDownload(status = HttpStatusCode.NotFound, hook = object : PassThroughHook() {
            override suspend fun beforeDownload(url: String): String = "$url?rewritten=true"
        }, expectedFailure = "HTTP 404")
    }

    @Test
    fun `successful download does not increment failures`() = runTest {
        checkDownload()
    }

    private suspend fun TestScope.checkDownload(
        status: HttpStatusCode = HttpStatusCode.OK,
        hook: DownloaderHook = PassThroughHook(),
        expectedFailure: String? = null
    ) {
        val client = HttpClient(MockEngine { respond("segment", status) }) { expectSuccess = true }
        val provider = object : HttpClientProvider {
            override fun direct(key: String, http1: Boolean, expectSuccess: Boolean) = client
            override fun proxied(key: String, http1: Boolean, expectSuccess: Boolean) = client
        }
        val eventBus = EventBus()
        val metric = MetricComponent(eventBus, backgroundScope)
        val downloader = DownloaderComponent(
            DataChannel(), listOf(hook), eventBus, backgroundScope, httpClientProvider = provider
        )
        val terminal = CompletableDeferred<Any>()
        val errors = mutableListOf<DownloadError>()
        val successes = mutableListOf<SegmentDownloaded>()
        eventBus.installHook(object : EventHook {
            override suspend fun intercept(event: Any): Any {
                when (event) {
                    is DownloadError -> { errors += event; terminal.complete(event) }
                    is SegmentDownloaded -> { successes += event; terminal.complete(event) }
                }
                return event
            }
        })
        val oldHosts = CdnSelector.hosts
        CdnSelector.updateHosts(emptyList())
        try {
            metric.start()
            downloader.start()
            runCurrent()
            eventBus.publish(RecordingStarted(ROOM_ID))
            runCurrent()
            downloader.tell(DoDownload(Download(ROOM_ID, listOf(Segment(URL, 0)), 0, 1)))
            terminal.await()
            runCurrent()

            val failed = if (expectedFailure == null) 0 else 1
            assertEquals(failed, errors.size)
            assertEquals(1 - failed, successes.size)
            if (expectedFailure != null) {
                assertTrue(errors.single().reason.startsWith(expectedFailure), errors.single().reason)
                assertEquals(URL, errors.single().url)
            }
            assertMetric(metric, "xhrec_attempted_total", 1)
            assertMetric(metric, "xhrec_failed_total", failed)
            assertMetric(metric, "xhrec_downloaded_total", 1 - failed)
            assertMetric(metric, "xhrec_downloading_current", 0)

            // Rotating the output file must preserve the cumulative failure count.
            eventBus.publish(FileReady(ROOM_ID, File("unused.mp4"), EndReason.SizeLimit, "model", 0, 1, 1, ""))
            runCurrent()
            assertMetric(metric, "xhrec_failed_total", failed)
        } finally {
            downloader.stop()
            metric.stop()
            CdnSelector.updateHosts(oldHosts)
            client.close()
        }
    }

    private fun assertMetric(metric: MetricComponent, name: String, expected: Int) {
        val text = metric.prometheusText()
        assertTrue("$name{roomId=\"$ROOM_ID\"} $expected" in text.lines(), text)
    }

    private open class PassThroughHook : DownloaderHook {
        override suspend fun beforeDownload(url: String) = url
        override suspend fun onDownloadResult(roomId: Long, result: DownloadResult) = result
    }

    private companion object {
        const val ROOM_ID = 11L
        const val URL = "http://localhost/segment.mp4"
    }
}
