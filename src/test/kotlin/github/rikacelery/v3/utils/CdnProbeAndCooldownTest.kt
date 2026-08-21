package github.rikacelery.v3.utils

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime
import java.time.ZoneId
import kotlin.test.*

class CdnProbeAndCooldownTest {

    private val zone = ZoneId.systemDefault()
    private val now = ZonedDateTime.of(2024, 1, 15, 10, 30, 0, 0, zone).toInstant().toEpochMilli()

    @BeforeEach
    fun setUp() {
        CdnSelector.reset()
        CdnSelector.updateHosts(listOf("cdn-a.com", "cdn-b.com"))
        CdnSelector.exploreProbability = 0.0
    }

    @Test
    fun single_failure_does_not_cooldown() {
        CdnSelector.recordFailure("cdn-a.com", now = now)
        val snap = CdnSelector.snapshot(now)["cdn-a.com"]!!
        assertEquals(1, snap.failures)
        // Not cooling after a single (transient) failure
        assertEquals(0L, snap.cooldownUntil)
    }

    @Test
    fun three_failures_trigger_cooldown() {
        CdnSelector.recordFailure("cdn-a.com", now)
        CdnSelector.recordFailure("cdn-a.com", now)
        val snapBefore = CdnSelector.snapshot(now)["cdn-a.com"]!!
        assertEquals(0L, snapBefore.cooldownUntil, "2 failures should not cooldown")

        CdnSelector.recordFailure("cdn-a.com", now)
        val snapAfter = CdnSelector.snapshot(now)["cdn-a.com"]!!
        assertTrue(snapAfter.cooldownUntil > now, "3 consecutive failures should cooldown")
    }

    @Test
    fun success_resets_failure_streak() {
        CdnSelector.recordFailure("cdn-a.com", now)
        CdnSelector.recordFailure("cdn-a.com", now)
        // A success resets the failure streak
        CdnSelector.record("cdn-a.com", 200, now = now)
        val snap = CdnSelector.snapshot(now)["cdn-a.com"]!!
        assertEquals(0, snap.failures, "success should reset failures")
    }

    @Test
    fun probe_records_independent_stats() {
        CdnSelector.probe("cdn-a.com", 150, now = now)
        CdnSelector.probe("cdn-a.com", 130, now = now)
        val ps = CdnSelector.probeSnapshot("cdn-a.com")
        assertNotNull(ps)
        assertEquals(2, ps.samples)
        assertEquals(0, ps.failures)
        assertEquals(2, ps.successes)
        assertTrue(ps.durationMs > 0)
        assertTrue(ps.lastSuccessful)
    }

    @Test
    fun probe_failure_does_not_cooldown() {
        CdnSelector.probeFailure("cdn-a.com")
        val ps = CdnSelector.probeSnapshot("cdn-a.com")
        assertNotNull(ps)
        assertEquals(1, ps.failures)
        assertFalse(ps.lastSuccessful)
        // probe failure must not cooldown the host
        val snap = CdnSelector.snapshot(now)["cdn-a.com"]!!
        assertEquals(0L, snap.cooldownUntil)
    }

    @Test
    fun clearCooldown_resets_all() {
        CdnSelector.recordFailure("cdn-a.com", now)
        CdnSelector.recordFailure("cdn-a.com", now)
        CdnSelector.recordFailure("cdn-a.com", now)
        val before = CdnSelector.snapshot(now)["cdn-a.com"]!!
        assertTrue(before.cooldownUntil > 0)

        CdnSelector.clearCooldown("cdn-a.com")
        val after = CdnSelector.snapshot(now)["cdn-a.com"]!!
        assertEquals(0L, after.cooldownUntil)
        assertEquals(0, after.failures)
    }

    @Test
    fun clearCooldown_clear_all_hosts() {
        CdnSelector.recordFailure("cdn-a.com", now)
        CdnSelector.recordFailure("cdn-a.com", now)
        CdnSelector.recordFailure("cdn-b.com", now)
        CdnSelector.recordFailure("cdn-b.com", now)
        CdnSelector.recordFailure("cdn-b.com", now)

        CdnSelector.clearCooldown("")
        val snap = CdnSelector.snapshot(now)
        assertTrue(snap["cdn-a.com"]!!.cooldownUntil == 0L)
        assertTrue(snap["cdn-b.com"]!!.cooldownUntil == 0L)
    }

    @Test
    fun probe_blends_into_selection() {
        // cdn-a has fast main data, cdn-b slower main but excellent probes
        repeat(5) {
            CdnSelector.record("cdn-a.com", 200, now = now)
            CdnSelector.record("cdn-b.com", 250, now = now)
        }
        // probe cdn-a as much slower, cdn-b as faster -> blended score may flip
        repeat(5) {
            CdnSelector.probe("cdn-b.com", 80, now = now)
            CdnSelector.probe("cdn-a.com", 400, now = now)
        }
        val selected = CdnSelector.select(now = now)
        // cdn-b blended: 250*0.8 + 80*0.2 = 216; cdn-a blended: 200*0.8 + 400*0.2 = 240
        assertEquals("cdn-b.com", selected, "Probe data should influence selection")
    }
}
