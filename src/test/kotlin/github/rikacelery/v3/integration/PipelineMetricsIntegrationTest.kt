package github.rikacelery.v3.integration

import io.ktor.client.statement.bodyAsText
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The bus, the data channel and the actor mailboxes had no exported counters at all: the bus and
 * the channel silently dropped messages with only a log line, and the mailbox depth existed but was
 * reachable only through `/diagnose`.
 *
 * Every assertion is a **before/after delta**, because the counters are process-wide and a previous
 * test in the same JVM can already have moved them — an absolute check would pass without this
 * wiring doing anything.
 */
class PipelineMetricsIntegrationTest {

    @Test
    fun `the bus, the channel and the actors are exported while recording`() = withFixture { fx ->
        fx.ready()
        val before = fx.get("/metrics").bodyAsText()

        fx.startRecording()

        val after = fx.get("/metrics").bodyAsText()

        assertGrew(before, after, "xhrec_eventbus_published_total", "event=\"SegmentDownloaded\"")
        assertGrew(before, after, "xhrec_datachannel_sent_total", "msg=\"StreamData\"")
        assertGrew(before, after, "xhrec_room_download_bytes_total", "roomId=\"1001\"")
        // SessionComponent, not WriterComponent: the writer consumes the DataChannel directly from
        // `onStart` and never goes through its own mailbox, so its actor counters stay at zero.
        assertGrew(before, after, "xhrec_actor_messages_total", "actor=\"SessionComponent\"")

        // Gauges, so they only have to be present and parse as a number.
        assertTrue(
            Regex("""xhrec_datachannel_pending \d+""").containsMatchIn(after),
            "the data channel backlog must be exported"
        )
        assertTrue(
            Regex("""xhrec_actor_mailbox_depth\{actor="SessionComponent"\} \d+""").containsMatchIn(after),
            "per-actor mailbox depth is what makes a lagging component visible"
        )
    }

    private fun assertGrew(before: String, after: String, family: String, labels: String) {
        val start = sample(before, family, labels)
        val end = sample(after, family, labels)
        assertTrue(
            end > start,
            "$family{$labels} must grow while a room records, went $start -> $end"
        )
    }

    /** Value of `family{labels}` in a Prometheus text payload, or 0 when the series is absent. */
    private fun sample(payload: String, family: String, labels: String): Long =
        Regex("""$family\{$labels\} (\d+)""").find(payload)?.groupValues?.get(1)?.toLong() ?: 0L
}
