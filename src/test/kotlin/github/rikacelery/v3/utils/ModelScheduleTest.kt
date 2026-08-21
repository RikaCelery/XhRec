package github.rikacelery.v3.utils

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime
import java.time.ZoneId
import kotlin.test.*

class ModelScheduleTest {

    private val systemZone = ZoneId.systemDefault()

    // Room IDs for testing
    private val room1 = 1001L
    private val room2 = 1002L
    private val room3 = 1003L

    // Monday 10:00
    private val monday10 = ZonedDateTime.of(2024, 1, 15, 10, 0, 0, 0, systemZone)
        .toInstant().toEpochMilli()
    // Monday 14:00
    private val monday14 = ZonedDateTime.of(2024, 1, 15, 14, 0, 0, 0, systemZone)
        .toInstant().toEpochMilli()
    // Tuesday 10:00
    private val tuesday10 = ZonedDateTime.of(2024, 1, 16, 10, 0, 0, 0, systemZone)
        .toInstant().toEpochMilli()
    // Saturday 20:00
    private val saturday20 = ZonedDateTime.of(2024, 1, 20, 20, 0, 0, 0, systemZone)
        .toInstant().toEpochMilli()

    @BeforeEach
    fun setUp() {
        ModelSchedule.reset()
    }

    @Test
    fun record_increases_total_count() {
        ModelSchedule.record(room1, monday10)
        ModelSchedule.record(room1, monday14)

        val snapshot = ModelSchedule.snapshot(room1)
        assertNotNull(snapshot)
        assertEquals(2, snapshot.totalCount)
    }

    @Test
    fun getDistribution_returns_null_for_unknown_room() {
        val dist = ModelSchedule.getDistribution(9999L)
        assertNull(dist)
    }

    @Test
    fun getDistribution_returns_correct_probabilities() {
        // Record 3 times at hour 10, 1 time at hour 14
        repeat(3) { ModelSchedule.record(room1, monday10) }
        ModelSchedule.record(room1, monday14)

        val dist = ModelSchedule.getDistribution(room1)
        assertNotNull(dist)
        assertEquals(24, dist.size)

        // Hour 10 should have 75% probability
        assertEquals(0.75, dist[10], 0.01)
        // Hour 14 should have 25% probability
        assertEquals(0.25, dist[14], 0.01)
        // Other hours should have 0%
        assertEquals(0.0, dist[0])
        assertEquals(0.0, dist[23])
    }

    @Test
    fun getTopHours_returns_top_n_hours() {
        // Record: hour 10 (5 times), hour 14 (3 times), hour 20 (2 times)
        repeat(5) { ModelSchedule.record(room1, monday10) }
        repeat(3) { ModelSchedule.record(room1, monday14) }
        repeat(2) { ModelSchedule.record(room1, saturday20) }

        val topHours = ModelSchedule.getTopHours(room1, n = 2)
        assertEquals(2, topHours.size)
        assertEquals(10, topHours[0].first)
        assertEquals(14, topHours[1].first)
    }

    @Test
    fun different_rooms_are_tracked_independently() {
        ModelSchedule.record(room1, monday10)
        ModelSchedule.record(room2, monday14)

        val snapshot1 = ModelSchedule.snapshot(room1)
        val snapshot2 = ModelSchedule.snapshot(room2)

        assertNotNull(snapshot1)
        assertNotNull(snapshot2)
        assertEquals(1, snapshot1.totalCount)
        assertEquals(1, snapshot2.totalCount)
    }

    @Test
    fun getAllRoomIds_returns_tracked_rooms() {
        ModelSchedule.record(room1, monday10)
        ModelSchedule.record(room2, monday14)
        ModelSchedule.record(room3, saturday20)

        val roomIds = ModelSchedule.getAllRoomIds()
        assertEquals(setOf(room1, room2, room3), roomIds)
    }

    @Test
    fun reset_clears_specific_room() {
        ModelSchedule.record(room1, monday10)
        ModelSchedule.record(room2, monday14)

        ModelSchedule.reset(room1)

        assertNull(ModelSchedule.snapshot(room1))
        assertNotNull(ModelSchedule.snapshot(room2))
    }

    @Test
    fun reset_clears_all_rooms() {
        ModelSchedule.record(room1, monday10)
        ModelSchedule.record(room2, monday14)

        ModelSchedule.reset()

        assertTrue(ModelSchedule.getAllRoomIds().isEmpty())
    }

    @Test
    fun snapshot_returns_null_for_unknown_room() {
        val snapshot = ModelSchedule.snapshot(9999L)
        assertNull(snapshot)
    }

    @Test
    fun snapshot_contains_correct_data() {
        repeat(5) { ModelSchedule.record(room1, monday10) }
        repeat(3) { ModelSchedule.record(room1, monday14) }

        val snapshot = ModelSchedule.snapshot(room1)
        assertNotNull(snapshot)
        assertEquals(room1, snapshot.roomId)
        assertEquals(8, snapshot.totalCount)
        assertTrue(snapshot.hourDistribution[10] > 0)
        assertTrue(snapshot.hourDistribution[14] > 0)
        assertTrue(snapshot.topHours.isNotEmpty())
    }
}
