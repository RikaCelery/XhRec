package github.rikacelery.v3.integration

import github.rikacelery.v3.events.PlaylistRefreshed
import io.ktor.client.statement.bodyAsText
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The exported gauges have to be fed by something. Two of them were not:
 *
 *  - nothing published [PlaylistRefreshed], so `xhrec_refresh_latency_ms` and
 *    `xhrec_segment_id_current` were declared, consumed and scraped while staying at 0 forever;
 *  - nothing distinguished "not downloading because the room is offline" from "not downloading
 *    while a session is running", because every per-room series outlives the session it describes.
 */
class MetricsPipelineIntegrationTest {

    @Test
    fun `each playlist poll reports its latency and newest segment id`() = withFixture { fx ->
        fx.ready()
        fx.startRecording()

        // The window is legitimately empty before the stream publishes anything, so the first
        // refresh or two carry no media ids; wait for one that does.
        val refreshed = fx.awaitEvent<PlaylistRefreshed>(10.seconds) {
            it.roomId == 1001L && it.maxSegmentId > 0
        }
        assertTrue(refreshed.latencyMs >= 0, "the fetch latency is measured: $refreshed")
        assertTrue(refreshed.maxSegmentId > 0, "the newest advertised id, not the init placeholder: $refreshed")

        val metrics = fx.get("/metrics").bodyAsText()
        assertTrue(
            Regex("""xhrec_refresh_latency_ms\{roomId="1001"\} [\d.]+""").containsMatchIn(metrics),
            "the refresh gauge must be exported for a recording room"
        )
        val currentId = Regex("""xhrec_segment_id_current\{roomId="1001"\} (\d+)""")
            .find(metrics)?.groupValues?.get(1)?.toLong()
        assertTrue((currentId ?: 0) > 0, "the current-segment gauge must carry the advertised id, got $currentId")
    }

    @Test
    fun `the recording gauge tracks whether a session is running`() = withFixture { fx ->
        fx.ready()
        fx.startRecording()
        assertEquals(1L, recordingGauge(fx), "a room with a running session must report recording=1")

        fx.get("/deactivate?id=1001")

        val off = fx.await(10.seconds, "the recording gauge to drop back to 0") {
            recordingGauge(fx).takeIf { it == 0L }
        }
        assertEquals(0L, off, "an offline room must report recording=0, or a stalled-room alert would fire on it")
    }

    /** Reads `xhrec_recording` for room 1001, or null when the room is not exported yet. */
    private suspend fun recordingGauge(fx: XhrecIntegrationFixture): Long? =
        Regex("""xhrec_recording\{roomId="1001"\} (\d+)""")
            .find(fx.get("/metrics").bodyAsText())?.groupValues?.get(1)?.toLong()
}
