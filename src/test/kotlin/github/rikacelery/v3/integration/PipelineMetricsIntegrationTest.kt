package github.rikacelery.v3.integration

import io.ktor.client.statement.bodyAsText
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
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

        // CDN attribution is per actual serving host, so the label value is the mock's host:port
        // and only the sum is stable enough to assert on.
        assertTrue(
            sumOf(after, "xhrec_cdn_segments_served_total") > sumOf(before, "xhrec_cdn_segments_served_total"),
            "the CDN host that actually served each segment must be counted"
        )
    }

    private fun sumOf(payload: String, family: String): Long =
        Regex("""$family\{host="[^"]*"\} (\d+)""").findAll(payload)
            .sumOf { it.groupValues[1].toLong() }

    @Test
    fun `websocket health and why a file was cut are exported`() = withFixture { fx ->
        fx.ready()
        fx.startRecording()

        val connected = fx.await(10.seconds, "the websocket gauge to report connected") {
            Regex("""xhrec_ws_connected 1""")
                .takeIf { it.containsMatchIn(fx.get("/metrics").bodyAsText()) }
                ?.let { true }
        }
        assertTrue(connected, "a connected live event source must be visible: losing it is silent")

        // `/break` cuts the file with a known reason, which must land as a label rather than only
        // as a log line.
        fx.get("/break?id=1001")
        val cut = fx.await(10.seconds, "the cut reason to be counted") {
            Regex("""xhrec_room_cut_total\{roomId="1001",reason="[A-Za-z]+"\} [1-9]""")
                .takeIf { it.containsMatchIn(fx.get("/metrics").bodyAsText()) }
                ?.let { true }
        }
        assertTrue(cut, "the reason a file was cut must be an alertable label")
    }

    @Test
    fun `actual segment duration is exported as a proper histogram`() = withFixture { fx ->
        fx.ready()
        val before = fx.get("/metrics").bodyAsText()
        fx.startRecording()
        val after = fx.get("/metrics").bodyAsText()

        // One metadata block per family however many hosts there are: a repeated HELP/TYPE makes
        // strict scrapers reject the whole payload.
        assertEquals(
            1, after.lines().count { it == "# TYPE xhrec_cdn_segment_duration_seconds histogram" },
            "the histogram family must be declared exactly once"
        )
        assertTrue(
            sumOf(after, "xhrec_cdn_segment_duration_seconds_count") >
                sumOf(before, "xhrec_cdn_segment_duration_seconds_count"),
            "every served segment must be observed"
        )

        val counts = Regex("""xhrec_cdn_segment_duration_seconds_count\{host="([^"]*)"\} (\d+)""")
            .findAll(after).associate { it.groupValues[1] to it.groupValues[2].toLong() }
        val buckets = Regex("""xhrec_cdn_segment_duration_seconds_bucket\{host="([^"]*)",le="([^"]+)"\} (\d+)""")
            .findAll(after)
            .groupBy({ it.groupValues[1] }, { it.groupValues[2] to it.groupValues[3].toLong() })

        assertTrue(buckets.isNotEmpty(), "at least one host must be exported")
        buckets.forEach { (host, entries) ->
            val values = entries.map { it.second }
            assertEquals(values.sorted(), values, "histogram buckets must be cumulative for $host")
            assertEquals(
                counts[host], entries.first { it.first == "+Inf" }.second,
                "the +Inf bucket must equal _count for $host"
            )
        }
    }

    @Test
    fun `session-scoped series disappear once a room stops recording`() = withFixture { fx ->
        fx.ready()
        fx.startRecording()

        val during = fx.get("/metrics").bodyAsText()
        assertTrue(
            """xhrec_bytes_write_total{roomId="1001"}""" in during,
            "the file being written is exported while the session runs"
        )
        assertTrue(
            """xhrec_segment_id_current{roomId="1001"}""" in during,
            "segment info is exported while the session runs"
        )

        fx.get("/deactivate?id=1001")

        val gone = fx.await(10.seconds, "the session-scoped series to be withdrawn") {
            val body = fx.get("/metrics").bodyAsText()
            ("""xhrec_bytes_write_total{roomId="1001"}""" !in body
                && """xhrec_segment_id_current{roomId="1001"}""" !in body
                && """xhrec_downloading_current{roomId="1001"}""" !in body
                && """xhrec_quality{roomId="1001"""" !in body).takeIf { it }
        }
        assertTrue(gone, "a frozen \"current file\" reads as a room that is still recording")

        // Lifetime counters stay, so a finished session is still worth looking at afterwards, and
        // the liveness gauge has to keep answering 0 rather than vanishing.
        val after = fx.get("/metrics").bodyAsText()
        assertTrue("""xhrec_downloaded_total{roomId="1001"}""" in after, "lifetime counters must survive")
        assertTrue("""xhrec_room_download_bytes_total{roomId="1001"}""" in after, "cumulative bytes must survive")
        assertTrue("""xhrec_recording{roomId="1001"} 0""" in after, "an offline room must report recording=0")
    }

    private fun assertGrew(before: String, after: String, family: String, labels: String) {
        val start = sample(before, family, labels)
        val end = sample(after, family, labels)
        assertTrue(
            end > start,
            "$family{$labels} must grow while a room records, went $start -> $end"
        )
    }

    @Test
    fun `room identity, arming and both state machines are exported`() = withFixture { fx ->
        fx.ready()
        fx.startRecording()

        val metrics = fx.get("/metrics").bodyAsText()
        for (expected in listOf(
            """xhrec_room_info{roomId="1001",status="public",quality="720p"} 1""",
            """xhrec_room_armed{roomId="1001"} 1""",
            """xhrec_scheduler_state{roomId="1001",state="Recording"} 1""",
            """xhrec_session_state{roomId="1001",state="Recording"} 1""",
        )) {
            assertTrue(expected in metrics, "missing $expected")
        }
        assertTrue(
            Regex("""xhrec_room_last_progress_seconds\{roomId="1001"\} [\d.]+""").containsMatchIn(metrics),
            "the progress clock is what makes a stalled room alertable without a window function"
        )
        assertTrue(
            Regex("""xhrec_room_resume_mark_ahead\{roomId="1001"\} \d+""").containsMatchIn(metrics),
            "a continuous gauge for the resume-mark backlog beats alerting on a sparse counter"
        )
    }

    @Test
    fun `why an armed room is not recording is exported as a label`() = withFixture { fx ->
        fx.ready()
        fx.mock.addRoom(1001, "model", status = "off")
        fx.get("/add?name=model&active=false")
        fx.get("/filter?id=1001&kind=public&v=false")
        fx.get("/activate?id=1001")
        fx.mock.startSegments(1001, 30.milliseconds)
        fx.awaitRoomSubscribed(1001)
        fx.mock.setRoomStatus(1001, "public")

        // The hint is computed on every scheduler transition, so it lands as soon as the room
        // status change is driven through the FSM.
        val hinted = fx.await(10.seconds, "the preconfig hint to be exported") {
            Regex("""xhrec_room_hint\{roomId="1001",code="public_filter_off"\} 1""")
                .takeIf { it.containsMatchIn(fx.get("/metrics").bodyAsText()) }
                ?.let { true }
        }
        assertTrue(hinted, "the reason a room cannot record must be an alertable label")
    }

    /** Value of `family{labels}` in a Prometheus text payload, or 0 when the series is absent. */
    private fun sample(payload: String, family: String, labels: String): Long =
        Regex("""$family\{$labels\} (\d+)""").find(payload)?.groupValues?.get(1)?.toLong() ?: 0L
}
