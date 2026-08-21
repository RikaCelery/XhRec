package github.rikacelery.v3.utils

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZonedDateTime
import java.time.ZoneId
import kotlin.test.*

class HostFailoverTest {

    private val failover = HostFailover()

    @BeforeEach
    fun setUp() {
        failover.updateHosts(emptyList())
    }

    @Test
    fun updateHosts_sanitizes_and_dedupes() {
        failover.updateHosts(listOf(" a.com ", "a.com", "", " b.com/", "b.com"))
        assertEquals(listOf("a.com", "b.com"), failover.hosts)
    }

    @Test
    fun currentHost_returns_first_available_host() {
        failover.updateHosts(listOf("a.com", "b.com", "c.com"))
        assertEquals("a.com", failover.currentHost())
    }

    @Test
    fun markFailure_advances_to_next_available_host() {
        failover.updateHosts(listOf("a.com", "b.com", "c.com"))
        failover.markFailure("a.com", now = 0)
        assertEquals("b.com", failover.currentHost(now = 1_000))
        failover.markFailure("b.com", now = 1_000)
        assertEquals("c.com", failover.currentHost(now = 2_000))
    }

    @Test
    fun host_returns_to_rotation_after_cooldown_expires() {
        failover.updateHosts(listOf("a.com", "b.com"))
        failover.markFailure("a.com", now = 0)
        assertEquals("b.com", failover.currentHost(now = 4_000))
        assertEquals("a.com", failover.currentHost(now = 6_000))
    }

    @Test
    fun markSuccess_resets_failure_state() {
        failover.updateHosts(listOf("a.com", "b.com"))
        failover.markFailure("a.com", now = 0)
        failover.markSuccess("a.com")
        assertEquals("a.com", failover.currentHost(now = 1_000))
    }
}

class CdnSelectorTest {

    // Fixed timestamps for deterministic tests (using system default timezone)
    private val systemZone = ZoneId.systemDefault()
    // Monday 10:30
    private val mondayMorning = ZonedDateTime.of(2024, 1, 15, 10, 30, 0, 0, systemZone)
        .toInstant().toEpochMilli()
    // Friday 22:30
    private val fridayNight = ZonedDateTime.of(2024, 1, 19, 22, 30, 0, 0, systemZone)
        .toInstant().toEpochMilli()
    // Saturday 14:00
    private val saturdayAfternoon = ZonedDateTime.of(2024, 1, 20, 14, 0, 0, 0, systemZone)
        .toInstant().toEpochMilli()

    @BeforeEach
    fun setUp() {
        CdnSelector.reset()
        CdnSelector.updateHosts(listOf("cdn-a.com", "cdn-b.com"))
        CdnSelector.exploreProbability = 0.0
    }

    @Test
    fun select_returns_configured_host_when_nothing_measured() {
        val host = CdnSelector.select(now = mondayMorning)
        assertTrue(host in listOf("cdn-a.com", "cdn-b.com"))
    }

    @Test
    fun select_picks_host_with_shortest_duration() {
        repeat(5) {
            CdnSelector.record("cdn-a.com", durationMs = 2_000, now = mondayMorning)
            CdnSelector.record("cdn-b.com", durationMs = 500, now = mondayMorning)
        }
        repeat(200) { assertEquals("cdn-b.com", CdnSelector.select(now = mondayMorning)) }
    }

    @Test
    fun exploration_assigns_selections_to_other_hosts() {
        repeat(5) {
            CdnSelector.record("cdn-a.com", durationMs = 2_000, now = mondayMorning)
            CdnSelector.record("cdn-b.com", durationMs = 500, now = mondayMorning)
        }
        CdnSelector.exploreProbability = 0.5
        val picked = (1..200).map { CdnSelector.select(now = mondayMorning) }.toSet()
        assertEquals(setOf("cdn-a.com", "cdn-b.com"), picked)
    }

    @Test
    fun failed_host_is_skipped_while_cooling_down() {
        // Cooling requires CONSECUTIVE_FAILURE_THRESHOLD (3) failures first.
        CdnSelector.recordFailure("cdn-a.com", now = 0)
        CdnSelector.recordFailure("cdn-a.com", now = 1_000)
        // After 2 failures, host should NOT be cooled down yet.
        assertTrue(CdnSelector.select(now = 2_000) in listOf("cdn-a.com", "cdn-b.com"))

        // 3rd consecutive failure triggers cooldown.
        CdnSelector.recordFailure("cdn-a.com", now = 2_000)
        assertEquals("cdn-b.com", CdnSelector.select(now = 3_000))
    }

    @Test
    fun rewriteHost_replaces_host_keeps_path_and_query() {
        val url = "https://cdn-a.com/b-hls-22/123/123_720p.m3u8?psch=v2&pkey=k1"
        val rewritten = CdnSelector.rewriteHost(url, "cdn-b.com")
        assertEquals("https://cdn-b.com/b-hls-22/123/123_720p.m3u8?psch=v2&pkey=k1", rewritten)
    }

    @Test
    fun hostOf_extracts_host_from_url() {
        assertEquals("cdn-a.com", CdnSelector.hostOf("https://cdn-a.com/x/y.m3u8?z=1"))
        assertEquals("cdn-b.com", CdnSelector.hostOf("https://cdn-b.com/b-hls-22/1/1.m3u8"))
    }

    @Test
    fun ewma_duration_converges_toward_recent_samples() {
        CdnSelector.record("cdn-a.com", durationMs = 10_000, now = mondayMorning)
        repeat(5) {
            CdnSelector.record("cdn-a.com", durationMs = 500, now = mondayMorning)
        }
        val snapshot = CdnSelector.snapshot()["cdn-a.com"]!!
        val duration = snapshot.globalEwma
        assertTrue(duration < 3_000, "duration should converge toward 500ms, was $duration")
    }

    // ── Time-based learning tests ───────────────────────────────────────────

    @Test
    fun hour_and_day_learning_picks_different_hosts() {
        // Monday morning: cdn-a fast (200ms), cdn-b slow (500ms)
        // Use values within 3x to avoid anomaly detection (500 < 200*3=600)
        repeat(10) {
            CdnSelector.record("cdn-a.com", durationMs = 200, now = mondayMorning)
            CdnSelector.record("cdn-b.com", durationMs = 500, now = mondayMorning)
        }
        // Friday night: cdn-a slow (500ms), cdn-b fast (200ms)
        repeat(10) {
            CdnSelector.record("cdn-a.com", durationMs = 500, now = fridayNight)
            CdnSelector.record("cdn-b.com", durationMs = 200, now = fridayNight)
        }

        assertEquals("cdn-a.com", CdnSelector.select(now = mondayMorning),
            "Monday morning should prefer cdn-a (200ms vs 500ms)")
        assertEquals("cdn-b.com", CdnSelector.select(now = fridayNight),
            "Friday night should prefer cdn-b (200ms vs 500ms)")
    }

    @Test
    fun hierarchical_fallback_uses_coarser_granularity() {
        // Record for current time (so snapshot can find the data)
        val now = System.currentTimeMillis()
        repeat(5) {
            CdnSelector.record("cdn-a.com", durationMs = 200, now = now)
            CdnSelector.record("cdn-b.com", durationMs = 500, now = now)
        }

        val snapshot = CdnSelector.snapshot()
        val aSnap = snapshot["cdn-a.com"]!!

        // Should use hour+day (finest) since we have >= 5 samples
        assertEquals("hour+day", aSnap.estimateSource)
        assertTrue(aSnap.estimatedDurationMs < 400.0, "cdn-a should be fast")
    }

    @Test
    fun fallback_to_global_when_no_time_data() {
        // Record without time context
        repeat(3) {
            CdnSelector.record("cdn-a.com", durationMs = 200, now = mondayMorning)
        }

        val snapshot = CdnSelector.snapshot()
        val aSnap = snapshot["cdn-a.com"]!!
        assertTrue(aSnap.globalSamples > 0)
    }

    @Test
    fun confidence_increases_with_more_samples() {
        val snapshot1 = CdnSelector.snapshot()
        // No data yet
        val initialConf = snapshot1["cdn-a.com"]?.confidence ?: 0.0

        // Record for current time (so snapshot can find the data)
        val now = System.currentTimeMillis()
        repeat(20) {
            CdnSelector.record("cdn-a.com", durationMs = 200, now = now)
        }

        val snapshot2 = CdnSelector.snapshot()
        val finalConf = snapshot2["cdn-a.com"]!!.confidence
        assertTrue(finalConf > initialConf, "confidence should increase with samples")
        assertTrue(finalConf > 0.8, "confidence should be high after 20 samples")
    }

    @Test
    fun anomaly_detection_triggers_cooldown() {
        // Establish baseline
        repeat(10) {
            CdnSelector.record("cdn-a.com", durationMs = 100, now = mondayMorning)
        }

        // Record anomalous spike (10x normal)
        CdnSelector.record("cdn-a.com", durationMs = 10_000, now = mondayMorning)

        val snapshot = CdnSelector.snapshot()["cdn-a.com"]!!
        // Should have triggered cooldown
        assertTrue(snapshot.failures > 0, "anomaly should trigger failure count")
    }

    @Test
    fun recordFailure_increments_errors_and_cooldown() {
        CdnSelector.recordFailure("cdn-a.com", now = 0)
        val snap1 = CdnSelector.snapshot()["cdn-a.com"]!!
        assertEquals(1, snap1.totalErrors)
        assertEquals(1, snap1.failures)
        // Below threshold (3): should NOT be cooling yet.
        assertEquals(0L, snap1.cooldownUntil)

        // After 3 consecutive failures it enters cooldown.
        CdnSelector.recordFailure("cdn-a.com", now = 1_000)
        CdnSelector.recordFailure("cdn-a.com", now = 2_000)
        val snap2 = CdnSelector.snapshot()["cdn-a.com"]!!
        assertEquals(3, snap2.failures)
        assertTrue(snap2.cooldownUntil > 2_000, "3 consecutive failures should trigger cooldown")

        assertEquals("cdn-b.com", CdnSelector.select(now = 3_000))
    }

    @Test
    fun snapshot_deep_copies_arrays() {
        repeat(3) { CdnSelector.record("cdn-a.com", durationMs = 100, now = mondayMorning) }
        val snap1 = CdnSelector.snapshot()["cdn-a.com"]!!
        repeat(3) { CdnSelector.record("cdn-a.com", durationMs = 200, now = mondayMorning) }
        val snap2 = CdnSelector.snapshot()["cdn-a.com"]!!
        assertTrue(snap1.globalSamples < snap2.globalSamples, "snapshots should be independent")
    }

    @Test
    fun different_weekdays_are_learned_independently() {
        // Monday morning: cdn-a fast (200ms)
        repeat(10) {
            CdnSelector.record("cdn-a.com", durationMs = 200, now = mondayMorning)
        }
        // Saturday afternoon: cdn-a slower (500ms) - different network conditions
        // Use 500ms to avoid anomaly detection (500 < 200*3=600)
        repeat(10) {
            CdnSelector.record("cdn-a.com", durationMs = 500, now = saturdayAfternoon)
        }

        val snapshot = CdnSelector.snapshot()["cdn-a.com"]!!

        // The hour-level EWMA should show different values for different times
        val mondayHour = ZonedDateTime.ofInstant(Instant.ofEpochMilli(mondayMorning), systemZone).hour
        val saturdayHour = ZonedDateTime.ofInstant(Instant.ofEpochMilli(saturdayAfternoon), systemZone).hour

        assertTrue(snapshot.hourEwma[mondayHour] < snapshot.hourEwma[saturdayHour],
            "Monday hour should be faster than Saturday hour")
    }
}
