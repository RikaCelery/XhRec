package github.rikacelery.v3.components

import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.events.DownloadStarted
import github.rikacelery.v3.events.RecordingStarted
import github.rikacelery.v3.events.RecordingStopped
import github.rikacelery.v3.events.SegmentDownloaded
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

    /**
     * A cut ends the session and a new one starts, writing a new file — so the byte counter has to
     * start over with it. Kept cumulative, a room that is being cut and restarted every ~10s reads
     * as a healthy multi-hundred-MB room; that is exactly how a restarting room was hiding in the
     * "Downloaded Bytes by Room" panel.
     */
    @Test
    fun `the session byte counter resets when a session restarts`() = runTest(UnconfinedTestDispatcher()) {
        val eventBus = EventBus()
        val metric = MetricComponent(eventBus, this)
        metric.start()
        try {
            assertTrue(metric.awaitSubscribed(), "metric actor must attach before publishing")
            eventBus.publish(RecordingStarted(7L, "720p"))
            eventBus.publish(SegmentDownloaded(7L, 0, "u1", 10L, false, 5_000, 1L))
            runCurrent()
            assertTrue(
                """xhrec_room_download_bytes_total{roomId="7"} 5000""" in metric.prometheusText(),
                "bytes accumulate inside one session"
            )

            // The session is cut (a stream change, an init change, a size limit) and restarted.
            eventBus.publish(RecordingStopped(7L))
            runCurrent()
            assertTrue(
                """xhrec_room_download_bytes_total{roomId="7"}""" !in metric.prometheusText(),
                "a stopped session withdraws its byte counter rather than freezing it"
            )

            eventBus.publish(RecordingStarted(7L, "720p"))
            runCurrent()
            assertTrue(
                """xhrec_room_download_bytes_total{roomId="7"} 0""" in metric.prometheusText(),
                "the restarted session starts from zero instead of carrying the previous total"
            )
        } finally {
            metric.stop()
        }
    }
}
