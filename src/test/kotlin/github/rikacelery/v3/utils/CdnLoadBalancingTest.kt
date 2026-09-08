package github.rikacelery.v3.utils

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime
import java.time.ZoneId
import kotlin.test.*

/**
 * Tests for the flexible CDN selection: near-best hosts share the load
 * (weighted by relative speed) instead of a strict winner-take-all.
 */
class CdnLoadBalancingTest {

    private val zone = ZoneId.systemDefault()
    private val now = ZonedDateTime.of(2024, 1, 15, 10, 30, 0, 0, zone).toInstant().toEpochMilli()

    @BeforeEach
    fun setUp() {
        CdnSelector.reset()
        CdnSelector.updateHosts(listOf("cdn-a.com", "cdn-b.com", "cdn-c.com"))
        CdnSelector.exploreProbability = 0.0
        // Selection consults the ML engine first; reset leaked models from other test classes.
        github.rikacelery.v3.ml.PredictionEngine.resetModels()
    }

    private fun recordAll(vararg hostMs: Pair<String, Long>, times: Int = 10) {
        repeat(times) {
            hostMs.forEach { (h, ms) -> CdnSelector.record(h, ms, now = now) }
        }
    }

    @Test
    fun near_equal_hosts_share_the_load() {
        // 100ms vs 110ms: gap within spreadTolerance (25%) + spreadAbsMs (30ms)
        recordAll("cdn-a.com" to 100L, "cdn-b.com" to 110L)

        val picks = (1..1000).map { CdnSelector.select(now = now) }
        val countA = picks.count { it == "cdn-a.com" }
        val countB = picks.count { it == "cdn-b.com" }

        assertTrue(countA > 0 && countB > 0, "both near-best hosts should get traffic: a=$countA b=$countB")
        // weight ratio 100:110 → expect roughly 52%/48%; allow a wide band for randomness
        assertTrue(countA in 350..650, "cdn-a share should be roughly half, was $countA/1000")
        assertTrue(countB in 350..650, "cdn-b share should be roughly half, was $countB/1000")
        // never picks the unscored third host
        assertEquals(0, picks.count { it == "cdn-c.com" })
    }

    @Test
    fun weight_favors_the_faster_host() {
        // 100ms vs 124ms: still inside the pool, but the better host must win more often
        recordAll("cdn-a.com" to 100L, "cdn-b.com" to 124L)

        val picks = (1..1000).map { CdnSelector.select(now = now) }
        val countA = picks.count { it == "cdn-a.com" }
        val countB = picks.count { it == "cdn-b.com" }

        assertTrue(countA > countB, "faster host should get more traffic: a=$countA b=$countB")
        assertTrue(countB > 250, "the slightly slower host should still get a fair share: b=$countB/1000")
    }

    @Test
    fun clearly_worse_host_gets_nothing() {
        // 100ms vs 500ms: far outside the spread → strict winner-take-all
        recordAll("cdn-a.com" to 100L, "cdn-b.com" to 500L)

        repeat(200) {
            assertEquals("cdn-a.com", CdnSelector.select(now = now))
        }
    }

    @Test
    fun absolute_slack_covers_tiny_durations() {
        // 4ms vs 10ms: relative gap is 150%, but the absolute slack (30ms) keeps them together
        recordAll("cdn-a.com" to 4L, "cdn-b.com" to 10L)

        val picks = (1..600).map { CdnSelector.select(now = now) }.toSet()
        assertEquals(setOf("cdn-a.com", "cdn-b.com"), picks)
    }

    @Test
    fun spread_zero_restores_winner_take_all() {
        CdnSelector.spreadTolerance = 0.0
        CdnSelector.spreadAbsMs = 0.0
        recordAll("cdn-a.com" to 100L, "cdn-b.com" to 101L)

        repeat(100) {
            assertEquals("cdn-a.com", CdnSelector.select(now = now))
        }
    }

    @Test
    fun rankedHosts_orders_best_first_and_excludes() {
        recordAll("cdn-a.com" to 100L, "cdn-b.com" to 200L, "cdn-c.com" to 300L)

        assertEquals(
            listOf("cdn-a.com", "cdn-b.com", "cdn-c.com"),
            CdnSelector.rankedHosts(now = now)
        )
        assertEquals(
            listOf("cdn-b.com", "cdn-c.com"),
            CdnSelector.rankedHosts(now = now, exclude = setOf("cdn-a.com"))
        )
    }

    @Test
    fun rankedHosts_skips_cooling_hosts_unless_asked() {
        recordAll("cdn-a.com" to 100L, "cdn-b.com" to 200L)
        repeat(3) { CdnSelector.recordFailure("cdn-a.com", now = now) } // triggers cooldown

        // cdn-a cooling → excluded; cdn-c has no score yet → appended after scored hosts
        assertEquals(listOf("cdn-b.com", "cdn-c.com"), CdnSelector.rankedHosts(now = now))
        assertEquals(
            listOf("cdn-a.com", "cdn-b.com", "cdn-c.com"),
            CdnSelector.rankedHosts(now = now, includeCooling = true)
        )
    }

    @Test
    fun spread_settings_survive_export_import() {
        CdnSelector.spreadTolerance = 0.4
        CdnSelector.spreadAbsMs = 55.0
        val json = CdnSelector.exportState()

        CdnSelector.reset()
        assertEquals(0.25, CdnSelector.spreadTolerance)

        CdnSelector.importState(json)
        assertEquals(0.4, CdnSelector.spreadTolerance)
        assertEquals(55.0, CdnSelector.spreadAbsMs)
    }
}
