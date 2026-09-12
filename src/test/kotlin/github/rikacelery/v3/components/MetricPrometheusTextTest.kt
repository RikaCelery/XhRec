package github.rikacelery.v3.components

import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.events.DownloadStarted
import github.rikacelery.v3.events.RecordingStarted
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MetricPrometheusTextTest {

    /**
     * Prometheus permits exactly one HELP/TYPE block per metric family. These used to be repeated
     * inside the per-room (and per-CDN-host) loops, which strict scrapers reject.
     */
    @Test
    fun `metric families are declared once for multiple rooms`() = runTest(UnconfinedTestDispatcher()) {
        val eventBus = EventBus()
        val metric = MetricComponent(eventBus, this)
        metric.start()
        try {
            assertTrue(metric.awaitSubscribed(), "metric actor must attach before publishing")
            eventBus.publish(RecordingStarted(1L, "720p"))
            eventBus.publish(RecordingStarted(2L, "720p"))
            eventBus.publish(DownloadStarted(1L, 0, "u1", 1L))
            eventBus.publish(DownloadStarted(2L, 0, "u2", 1L))
            runCurrent()

            val text = metric.prometheusText()
            assertEquals(1, metadataCount(text, "# HELP", "xhrec_attempted_total"))
            assertEquals(1, metadataCount(text, "# TYPE", "xhrec_attempted_total"))
            assertEquals(1, metadataCount(text, "# HELP", "xhrec_quality"))
            assertTrue("xhrec_attempted_total{roomId=\"1\"} 1" in text, text)
            assertTrue("xhrec_attempted_total{roomId=\"2\"} 1" in text, text)
        } finally {
            metric.stop()
        }
    }

    private fun metadataCount(text: String, prefix: String, family: String): Int =
        Regex("^${Regex.escape(prefix)} ${Regex.escape(family)} ", RegexOption.MULTILINE)
            .findAll(text)
            .count()
}
