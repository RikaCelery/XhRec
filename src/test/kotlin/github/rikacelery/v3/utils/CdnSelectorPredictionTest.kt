package github.rikacelery.v3.utils

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime
import java.time.ZoneId
import kotlin.test.*
import kotlin.random.Random

/**
 * Comprehensive tests for CDN selector prediction accuracy.
 * Simulates real-world scenarios to verify the learning algorithm works correctly.
 */
class CdnSelectorPredictionTest {

    private val systemZone = ZoneId.systemDefault()
    private val random = Random(42) // Fixed seed for reproducibility

    // Time slots for testing
    private fun createTimestamp(day: Int, hour: Int): Long {
        return ZonedDateTime.of(2024, 1, day, hour, 30, 0, 0, systemZone)
            .toInstant().toEpochMilli()
    }

    // Monday slots
    private val mondayMorning = createTimestamp(15, 10)   // Monday 10:30
    private val mondayAfternoon = createTimestamp(15, 14)  // Monday 14:30
    private val mondayNight = createTimestamp(15, 22)      // Monday 22:30

    // Friday slots
    private val fridayMorning = createTimestamp(19, 10)    // Friday 10:30
    private val fridayNight = createTimestamp(19, 22)      // Friday 22:30

    // Saturday slots
    private val saturdayMorning = createTimestamp(20, 10)  // Saturday 10:30
    private val saturdayAfternoon = createTimestamp(20, 15) // Saturday 15:30

    @BeforeEach
    fun setUp() {
        CdnSelector.reset()
        CdnSelector.updateHosts(listOf("cdn-a.com", "cdn-b.com", "cdn-c.com"))
        CdnSelector.exploreProbability = 0.0 // Disable exploration for deterministic tests
    }

    // -- Test 1: Basic prediction accuracy --

    @Test
    fun test_basic_prediction_accuracy() {
        println("=== Test 1: Basic Prediction Accuracy ===")

        // Scenario: cdn-a is consistently fast (100-150ms)
        //           cdn-b is consistently slow (500-600ms)
        repeat(20) {
            val jitter = random.nextLong(-20, 20)
            CdnSelector.record("cdn-a.com", 120 + jitter, now = mondayMorning)
            CdnSelector.record("cdn-b.com", 550 + jitter, now = mondayMorning)
        }

        // Verify cdn-a is preferred
        var correct = 0
        repeat(100) {
            if (CdnSelector.select(now = mondayMorning) == "cdn-a.com") correct++
        }

        println("Correct predictions: $correct/100")
        assertTrue(correct >= 95, "Should prefer cdn-a at least 95% of time, got $correct%")

        // Check estimated duration
        val snapshot = CdnSelector.snapshot(mondayMorning)
        val aSnap = snapshot["cdn-a.com"]!!
        val bSnap = snapshot["cdn-b.com"]!!

        println("cdn-a estimated: ${aSnap.estimatedDurationMs.toLong()}ms, source: ${aSnap.estimateSource}")
        println("cdn-b estimated: ${bSnap.estimatedDurationMs.toLong()}ms, source: ${bSnap.estimateSource}")

        assertTrue(aSnap.estimatedDurationMs < 200, "cdn-a should be ~120ms")
        assertTrue(bSnap.estimatedDurationMs > 200, "cdn-b should be ~550ms")
    }

    // -- Test 2: Time-of-day awareness --

    @Test
    fun test_time_of_day_awareness() {
        println("\n=== Test 2: Time-of-Day Awareness ===")

        // Scenario: cdn-a is fast in morning, slow at night
        //           cdn-b is slow in morning, fast at night
        // Use values within 3x to avoid anomaly detection (250 < 100*3=300)
        repeat(15) {
            CdnSelector.record("cdn-a.com", 100, now = mondayMorning)
            CdnSelector.record("cdn-b.com", 250, now = mondayMorning)
        }
        repeat(15) {
            CdnSelector.record("cdn-a.com", 250, now = mondayNight)
            CdnSelector.record("cdn-b.com", 100, now = mondayNight)
        }

        // Verify time-aware selection
        val morningPick = CdnSelector.select(now = mondayMorning)
        val nightPick = CdnSelector.select(now = mondayNight)

        println("Morning pick: $morningPick (expected: cdn-a.com)")
        println("Night pick: $nightPick (expected: cdn-b.com)")

        assertEquals("cdn-a.com", morningPick, "Morning should prefer cdn-a")
        assertEquals("cdn-b.com", nightPick, "Night should prefer cdn-b")
    }

    // -- Test 3: Hierarchical fallback --

    @Test
    fun test_hierarchical_fallback() {
        println("\n=== Test 3: Hierarchical Fallback ===")

        // Record enough data for hour+day (finest granularity)
        repeat(10) {
            CdnSelector.record("cdn-a.com", 150, now = mondayMorning)
            CdnSelector.record("cdn-b.com", 600, now = mondayMorning)
        }

        val snapshot = CdnSelector.snapshot(mondayMorning)
        val aSnap = snapshot["cdn-a.com"]!!

        println("Estimate source: ${aSnap.estimateSource}")
        println("Confidence: ${aSnap.confidence}")

        assertEquals("hour+day", aSnap.estimateSource, "Should use finest granularity")
        assertTrue(aSnap.confidence > 0.5, "Confidence should be reasonable")
    }

    // -- Test 4: Anomaly detection --

    @Test
    fun test_anomaly_detection() {
        println("\n=== Test 4: Anomaly Detection ===")

        // Establish baseline
        repeat(10) {
            CdnSelector.record("cdn-a.com", 100, now = mondayMorning)
        }

        val snapshotBefore = CdnSelector.snapshot(mondayMorning)["cdn-a.com"]!!
        println("Failures before anomaly: ${snapshotBefore.failures}")

        // Single anomaly (5x normal) should NOT cooldown yet (needs 2 consecutive).
        CdnSelector.record("cdn-a.com", 500, now = mondayMorning)
        val snapshotOne = CdnSelector.snapshot(mondayMorning)["cdn-a.com"]!!
        println("After single anomaly, failures: ${snapshotOne.failures}")

        // Second consecutive anomaly triggers cooldown.
        CdnSelector.record("cdn-a.com", 500, now = mondayMorning)
        val snapshotAfter = CdnSelector.snapshot(mondayMorning)["cdn-a.com"]!!
        println("Failures after anomalies: ${snapshotAfter.failures}")
        println("Cooldown until: ${snapshotAfter.cooldownUntil}")
        println("Monday morning: $mondayMorning")
        println("Cooling down: ${snapshotAfter.cooldownUntil > mondayMorning}")

        assertTrue(snapshotAfter.failures > 0, "Anomaly should trigger failure count")
        // 2 consecutive anomalies trigger cooldown.
        assertTrue(snapshotAfter.cooldownUntil > mondayMorning, "2 consecutive anomalies should cool down")
    }

    // -- Test 5: Confidence scoring --

    @Test
    fun test_confidence_scoring() {
        println("\n=== Test 5: Confidence Scoring ===")

        // No data = zero confidence
        val snapshot0 = CdnSelector.snapshot(mondayMorning)
        val conf0 = snapshot0["cdn-a.com"]?.confidence ?: 0.0
        println("Confidence with 0 samples: $conf0")

        // Few samples = low confidence
        repeat(2) {
            CdnSelector.record("cdn-a.com", 100, now = mondayMorning)
        }
        val snapshot1 = CdnSelector.snapshot(mondayMorning)
        val conf1 = snapshot1["cdn-a.com"]!!.confidence
        println("Confidence with 2 samples: $conf1")

        // Many samples = high confidence
        repeat(20) {
            CdnSelector.record("cdn-a.com", 100, now = mondayMorning)
        }
        val snapshot2 = CdnSelector.snapshot(mondayMorning)
        val conf2 = snapshot2["cdn-a.com"]!!.confidence
        println("Confidence with 22 samples: $conf2")

        assertTrue(conf0 < conf1, "Confidence should increase with samples")
        assertTrue(conf1 < conf2, "More samples = higher confidence")
        assertTrue(conf2 > 0.8, "22 samples should give high confidence")
    }

    // -- Test 6: Weekend vs weekday differentiation --

    @Test
    fun test_weekend_vs_weekday() {
        println("\n=== Test 6: Weekend vs Weekday ===")

        // Monday (weekday): cdn-a fast (100ms), cdn-b slower (250ms)
        // Use values within 3x to avoid anomaly detection
        repeat(10) {
            CdnSelector.record("cdn-a.com", 100, now = mondayMorning)
            CdnSelector.record("cdn-b.com", 250, now = mondayMorning)
        }

        // Saturday (weekend): cdn-b fast (100ms), cdn-a slower (250ms)
        repeat(10) {
            CdnSelector.record("cdn-a.com", 250, now = saturdayMorning)
            CdnSelector.record("cdn-b.com", 100, now = saturdayMorning)
        }

        val snapshot = CdnSelector.snapshot(mondayMorning)
        val aSnap = snapshot["cdn-a.com"]!!

        println("cdn-a Monday hour 10: ${aSnap.hourEwma[10].toLong()}ms")

        // The hour-level EWMA should reflect different performance
        val mondayPick = CdnSelector.select(now = mondayMorning)
        val saturdayPick = CdnSelector.select(now = saturdayMorning)

        println("Monday morning pick: $mondayPick")
        println("Saturday morning pick: $saturdayPick")

        assertEquals("cdn-a.com", mondayPick, "Monday should prefer cdn-a")
        assertEquals("cdn-b.com", saturdayPick, "Saturday should prefer cdn-b")
    }

    // -- Test 7: Gradual performance shift --

    @Test
    fun test_gradual_performance_shift() {
        println("\n=== Test 7: Gradual Performance Shift ===")

        // Initially cdn-a is fast
        repeat(10) {
            CdnSelector.record("cdn-a.com", 100, now = mondayMorning)
            CdnSelector.record("cdn-b.com", 500, now = mondayMorning)
        }

        val pick1 = CdnSelector.select(now = mondayMorning)
        println("Initial pick: $pick1 (expected: cdn-a.com)")
        assertEquals("cdn-a.com", pick1)

        // cdn-a degrades gradually (not anomaly, just slower)
        repeat(15) {
            CdnSelector.record("cdn-a.com", 400, now = mondayMorning)
            CdnSelector.record("cdn-b.com", 500, now = mondayMorning)
        }

        val pick2 = CdnSelector.select(now = mondayMorning)
        println("After degradation: $pick2 (should still be cdn-a or similar)")

        // The EWMA should have adapted
        val snapshot = CdnSelector.snapshot(mondayMorning)
        val aDuration = snapshot["cdn-a.com"]!!.estimatedDurationMs
        println("cdn-a estimated duration: ${aDuration.toLong()}ms (should be ~300-400ms)")
    }

    // -- Test 8: Multiple CDN competition --

    @Test
    fun test_multiple_cdn_competition() {
        println("\n=== Test 8: Multiple CDN Competition ===")

        // Three CDNs with different performance characteristics
        repeat(20) {
            CdnSelector.record("cdn-a.com", 150 + random.nextLong(-10, 10), now = mondayMorning)
            CdnSelector.record("cdn-b.com", 200 + random.nextLong(-15, 15), now = mondayMorning)
            CdnSelector.record("cdn-c.com", 300 + random.nextLong(-20, 20), now = mondayMorning)
        }

        // Count selections
        val selections = mutableMapOf<String, Int>()
        repeat(100) {
            val sel = CdnSelector.select(now = mondayMorning)
            selections[sel] = (selections[sel] ?: 0) + 1
        }

        println("Selection distribution: $selections")

        // cdn-a should be selected most often
        assertTrue((selections["cdn-a.com"] ?: 0) > 80, "cdn-a should dominate")
    }

    // -- Test 9: Snapshot API correctness --

    @Test
    fun test_snapshot_api() {
        println("\n=== Test 9: Snapshot API Correctness ===")

        repeat(10) {
            CdnSelector.record("cdn-a.com", 100, now = mondayMorning)
        }

        val snapshot = CdnSelector.snapshot(mondayMorning)
        val aSnap = snapshot["cdn-a.com"]!!

        println("Snapshot data:")
        println("  estimatedDurationMs: ${aSnap.estimatedDurationMs}")
        println("  estimateSource: ${aSnap.estimateSource}")
        println("  confidence: ${aSnap.confidence}")
        println("  globalEwma: ${aSnap.globalEwma}")
        println("  globalSamples: ${aSnap.globalSamples}")
        println("  totalSuccesses: ${aSnap.totalSuccesses}")
        println("  totalErrors: ${aSnap.totalErrors}")

        assertTrue(aSnap.estimatedDurationMs in 80.0..150.0, "Should be ~100ms")
        assertEquals("hour+day", aSnap.estimateSource)
        assertTrue(aSnap.confidence > 0.5)
        assertEquals(10, aSnap.globalSamples)
        assertEquals(10, aSnap.totalSuccesses)
        assertEquals(0, aSnap.totalErrors)
    }

    // -- Test 10: Edge case - very few samples --

    @Test
    fun test_edge_case_few_samples() {
        println("\n=== Test 10: Edge Case - Few Samples ===")

        // Only 1 sample
        CdnSelector.record("cdn-a.com", 100, now = mondayMorning)

        val snapshot = CdnSelector.snapshot(mondayMorning)
        val aSnap = snapshot["cdn-a.com"]!!

        println("With 1 sample:")
        println("  estimateSource: ${aSnap.estimateSource}")
        println("  estimatedDuration: ${aSnap.estimatedDurationMs}")
        println("  confidence: ${aSnap.confidence}")

        // Should still provide an estimate
        assertTrue(aSnap.estimatedDurationMs > 0, "Should have an estimate")
        assertTrue(aSnap.confidence > 0, "Should have some confidence")
    }
}