package github.rikacelery.v3.utils

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The occupancy history is what replaced "only record when a room went live": a ten-minute grid per
 * room, from which both the show-records grid and the open/closed forecast are derived.
 */
class ScheduleHistoryTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    /**
     * Room ids no other suite uses, plus a removal of exactly those: the history is process-global,
     * and an integration test running in the same JVM samples real rooms 1001..1003 into it.
     */
    private val r1 = 9_000_001L
    private val r2 = 9_000_002L
    private val r3 = 9_000_003L
    private val r4 = 9_000_004L
    private val r5 = 9_000_005L

    /** A timestamp on a given number of days ago, at [hour]:[minute]. */
    private fun at(daysAgo: Long, hour: Int, minute: Int): Long =
        LocalDate.now(zone).minusDays(daysAgo)
            .atTime(hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    @AfterEach
    fun tearDown() {
        listOf(r1, r2, r3, r4, r5, 7L, 8L).forEach { ScheduleHistory.reset(it) }
    }

    @Test
    fun `every slot is filed under the kind of show that was running`() {
        val now = at(0, 12, 25)
        ScheduleHistory.sample(
            mapOf(
                r1 to "public",
                r2 to "groupShow",
                r3 to "virtualPrivate",
                r4 to "p2p",
                r5 to "off"
            ),
            now
        )

        val slot = ScheduleHistory.slotOf(now)
        assertEquals(12 * 6 + 2, slot, "12:25 is the third ten-minute slot of the hour")
        fun day(roomId: Long) = ScheduleHistory.history(roomId, 1, now).single().slots
        assertEquals(ScheduleHistory.CODE_PUBLIC, day(r1)[slot])
        assertEquals(ScheduleHistory.CODE_TICKET, day(r2)[slot])
        assertEquals(ScheduleHistory.CODE_PRIVATE, day(r3)[slot])
        assertEquals(ScheduleHistory.CODE_P2P, day(r4)[slot])
        assertEquals(ScheduleHistory.CLOSED, day(r5)[slot])
        // Slots nobody has sampled yet are unknown, not closed: a restart must not read as evidence
        // that the room was off.
        assertEquals(ScheduleHistory.UNKNOWN, day(r1)[slot - 1])
        assertTrue(ScheduleHistory.isOpen(ScheduleHistory.CODE_PRIVATE))
        assertTrue(!ScheduleHistory.isOpen(ScheduleHistory.CLOSED))
        assertTrue(!ScheduleHistory.isOpen(ScheduleHistory.UNKNOWN))
    }

    @Test
    fun `the same slot is only recorded once per pass`() {
        val now = at(0, 9, 3)
        assertTrue(ScheduleHistory.sample(mapOf(r1 to "public"), now), "a new slot is a change")
        assertTrue(!ScheduleHistory.sample(mapOf(r1 to "public"), now + 60_000), "same state, no change")
        assertTrue(ScheduleHistory.sample(mapOf(r1 to "off"), now + 120_000), "a status change is a change")
        val slots = ScheduleHistory.history(r1, 1, now).single().slots
        assertEquals(ScheduleHistory.CLOSED, slots[ScheduleHistory.slotOf(now)])
    }

    @Test
    fun `the forecast follows the weekday the room usually works`() {
        // Four weeks of Mondays: open 20:00-21:00, closed otherwise. The grid has to pick that up
        // even though each cell only has four observations.
        val monday = LocalDate.now(zone).minusDays(7 * 4).with(java.time.DayOfWeek.MONDAY)
        var open = 0
        var closed = 0
        for (week in 0 until 4) {
            val day = monday.plusWeeks(week.toLong())
            val statuses = mapOf(r1 to "public")
            // 20:00-20:50 open, plus one closed slot earlier so "closed" is learned too.
            for (slotMinute in 0 until 60 step 10) {
                ScheduleHistory.sample(statuses, day.atTime(20, slotMinute).atZone(zone).toInstant().toEpochMilli())
                open++
            }
            ScheduleHistory.sample(mapOf(r1 to "off"), day.atTime(3, 0).atZone(zone).toInstant().toEpochMilli())
            closed++
        }
        assertTrue(open > 0 && closed > 0)

        val forecast = ScheduleHistory.forecast(r1, 7)
        val mondayForecast = forecast.first { LocalDate.parse(it.date).dayOfWeek == java.time.DayOfWeek.MONDAY }
        val evening = mondayForecast.slots[20 * 6]
        val night = mondayForecast.slots[3 * 6]
        assertTrue(evening > 0.6, "20:00 on a Monday is a regular slot, got $evening")
        assertTrue(night < 0.2, "03:00 is never worked, got $night")
    }

    @Test
    fun `history keeps only the requested window, newest first`() {
        ScheduleHistory.sample(mapOf(r1 to "public"), at(0, 10, 0))
        ScheduleHistory.sample(mapOf(r1 to "public"), at(2, 10, 0))
        ScheduleHistory.sample(mapOf(r1 to "public"), at(5, 10, 0))

        val three = ScheduleHistory.history(r1, 3)
        assertEquals(2, three.size, "the sample five days back is outside a three-day window")
        assertEquals(ScheduleHistory.dayOf(at(0, 10, 0)), three.first().date)
        assertTrue(three.first().date > three.last().date, "newest first")
    }

    @Test
    fun `days past the retention window are dropped`() {
        ScheduleHistory.sample(mapOf(r1 to "public"), at(ScheduleHistory.KEEP_DAYS + 10, 10, 0))
        ScheduleHistory.sample(mapOf(r1 to "public"), at(0, 10, 0))
        assertEquals(2, ScheduleHistory.history(r1, 400).size)

        ScheduleHistory.cleanup()

        val kept = ScheduleHistory.history(r1, 400)
        assertEquals(1, kept.size, "only today's day survives the cleanup")
    }

    @Test
    fun `the history round-trips through persistence`() {
        val now = Instant.now().toEpochMilli()
        ScheduleHistory.sample(mapOf(7L to "public", 8L to "groupShow"), now)
        val exported = ScheduleHistory.exportState()

        ScheduleHistory.reset(7L)
        ScheduleHistory.reset(8L)
        assertTrue(ScheduleHistory.history(7L, 1, now).isEmpty())

        ScheduleHistory.importState(exported)
        assertTrue(ScheduleHistory.rooms().containsAll(setOf(7L, 8L)))
        val slots = ScheduleHistory.history(8L, 1, now).single().slots
        assertEquals(ScheduleHistory.CODE_TICKET, slots[ScheduleHistory.slotOf(now)])
        assertEquals(ScheduleHistory.SLOTS_PER_DAY, slots.length)

        // Garbage must not throw away what is already in memory.
        ScheduleHistory.importState("{not json")
        assertTrue(ScheduleHistory.rooms().containsAll(setOf(7L, 8L)))
    }
}
