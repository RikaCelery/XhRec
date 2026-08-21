package github.rikacelery.v3.utils

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime
import java.time.ZoneId
import kotlin.test.*

class ModelSchedulePredictionTest {

    private val systemZone = ZoneId.systemDefault()
    private val room1 = 1001L
    private val room2 = 1002L
    private val room3 = 1003L

    private fun createTimestamp(day: Int, hour: Int, minute: Int = 0): Long {
        return ZonedDateTime.of(2024, 1, day, hour, minute, 0, 0, systemZone)
            .toInstant().toEpochMilli()
    }

    private val room1MorningSlots = listOf(
        createTimestamp(15, 10, 30),
        createTimestamp(16, 10, 15),
        createTimestamp(17, 10, 45),
        createTimestamp(18, 10, 20),
        createTimestamp(19, 10, 0),
        createTimestamp(20, 10, 30),
        createTimestamp(21, 10, 15),
    )

    private val room2EveningSlots = listOf(
        createTimestamp(15, 20, 0),
        createTimestamp(16, 20, 30),
        createTimestamp(17, 20, 15),
        createTimestamp(18, 20, 45),
        createTimestamp(19, 20, 0),
        createTimestamp(20, 20, 30),
        createTimestamp(21, 20, 15),
    )

    @BeforeEach
    fun setUp() {
        ModelSchedule.reset()
    }

    @Test
    fun test_basic_prediction_accuracy() {
        println("=== Test 1: Basic Prediction Accuracy ===")
        room1MorningSlots.forEach { ModelSchedule.record(room1, it) }
        val snapshot = ModelSchedule.snapshot(room1)!!
        println("Total recordings: ${snapshot.totalCount}")
        println("Top hours: ${snapshot.topHours}")
        assertTrue(snapshot.topHours.isNotEmpty())
        assertEquals(10, snapshot.topHours[0].first, "Top hour should be 10")
    }

    @Test
    fun test_distribution_accuracy() {
        println("\n=== Test 2: Distribution Accuracy ===")
        room1MorningSlots.forEach { ModelSchedule.record(room1, it) }
        val distribution = ModelSchedule.getDistribution(room1)!!
        assertEquals(24, distribution.size)
        val hour10Prob = distribution[10]
        println("Hour 10 probability: ${hour10Prob}")
        assertTrue(hour10Prob > 0.5, "Hour 10 should have >50% probability")
    }

    @Test
    fun test_multiple_rooms_independence() {
        println("\n=== Test 3: Multiple Rooms Independence ===")
        room1MorningSlots.forEach { ModelSchedule.record(room1, it) }
        room2EveningSlots.forEach { ModelSchedule.record(room2, it) }
        val s1 = ModelSchedule.snapshot(room1)!!
        val s2 = ModelSchedule.snapshot(room2)!!
        println("Room1 top hours: ${s1.topHours}")
        println("Room2 top hours: ${s2.topHours}")
        assertEquals(10, s1.topHours[0].first, "Room1 should prefer hour 10")
        assertEquals(20, s2.topHours[0].first, "Room2 should prefer hour 20")
    }

    @Test
    fun test_top_hours_ranking() {
        println("\n=== Test 4: Top Hours Ranking ===")
        room1MorningSlots.forEach { ModelSchedule.record(room1, it) }
        ModelSchedule.record(room1, createTimestamp(22, 11, 0))
        ModelSchedule.record(room1, createTimestamp(23, 11, 0))
        ModelSchedule.record(room1, createTimestamp(24, 14, 0))
        val topHours = ModelSchedule.getTopHours(room1, n = 3)
        println("Top 3 hours: ${topHours}")
        assertTrue(topHours.size >= 2, "Should return at least 2 top hours")
        for (i in 0 until topHours.size - 1) {
            assertTrue(topHours[i].second >= topHours[i + 1].second)
        }
        assertEquals(10, topHours[0].first, "Top hour should be 10")
    }

    @Test
    fun test_snapshot_api() {
        println("\n=== Test 5: Snapshot API Correctness ===")
        room1MorningSlots.forEach { ModelSchedule.record(room1, it) }
        val s = ModelSchedule.snapshot(room1)!!
        println("roomId: ${s.roomId}, totalCount: ${s.totalCount}, recentCount: ${s.recentCount}")
        assertEquals(room1, s.roomId)
        assertEquals(room1MorningSlots.size.toLong(), s.totalCount)
        assertTrue(s.topHours.isNotEmpty())
    }

    @Test
    fun test_edge_case_single_recording() {
        println("\n=== Test 6: Edge Case - Single Recording ===")
        ModelSchedule.record(room1, createTimestamp(15, 10, 30))
        val s = ModelSchedule.snapshot(room1)!!
        println("totalCount: ${s.totalCount}, topHours: ${s.topHours}")
        assertEquals(1, s.totalCount)
        assertEquals(10, s.topHours[0].first)
    }

    @Test
    fun test_get_all_room_ids() {
        println("\n=== Test 7: GetAllRoomIds ===")
        ModelSchedule.record(room1, createTimestamp(15, 10))
        ModelSchedule.record(room2, createTimestamp(15, 20))
        ModelSchedule.record(room3, createTimestamp(15, 15))
        val ids = ModelSchedule.getAllRoomIds()
        println("All room IDs: ${ids}")
        assertEquals(3, ids.size)
    }

    @Test
    fun test_reset_functionality() {
        println("\n=== Test 8: Reset Functionality ===")
        ModelSchedule.record(room1, createTimestamp(15, 10))
        ModelSchedule.record(room2, createTimestamp(15, 20))
        ModelSchedule.reset(room1)
        assertNull(ModelSchedule.snapshot(room1))
        assertNotNull(ModelSchedule.snapshot(room2))
        ModelSchedule.reset()
        assertTrue(ModelSchedule.getAllRoomIds().isEmpty())
    }

    @Test
    fun test_predict_live_soon() {
        println("\n=== Test 9: Predict Live Soon ===")
        room1MorningSlots.forEach { ModelSchedule.record(room1, it) }
        val prob = ModelSchedule.predictLiveSoon(room1, lookaheadHours = 2)
        println("Probability: ${prob}")
        if (prob != null) assertTrue(prob in 0.0..1.0)
    }

    @Test
    fun test_next_predicted_hour() {
        println("\n=== Test 10: Next Predicted Hour ===")
        room1MorningSlots.forEach { ModelSchedule.record(room1, it) }
        val h = ModelSchedule.getNextPredictedHour(room1)
        println("Next predicted hour: ${h}")
        if (h != null) assertTrue(h in 0..23)
    }
}
