package github.rikacelery.cutter

import github.rikacelery.cutter.events.EventKind
import github.rikacelery.cutter.events.EventParser
import github.rikacelery.cutter.events.ParsedEvent
import github.rikacelery.cutter.events.Timeline
import github.rikacelery.cutter.events.ToyTimeline
import github.rikacelery.cutter.media.Power
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Toy interval construction.
 *
 * The behaviour under test is the FIFO consequence that matters for cutting: chained
 * commands have to fuse into one block, the backlog tail after the last gift has to
 * survive, and a stop command has to cut the block short.
 */
class ToyTimelineTest {

    /** A `lovense` command already placed on the media timeline. */
    private fun toyCommand(
        line: Int,
        seconds: Double,
        duration: Double?,
        action: String? = null,
        power: Power = Power.LOW
    ) = ParsedEvent(
        lineIndex = line,
        kind = EventKind.TOY_CMD,
        channel = "newChatMessage",
        ownEpochMs = 0L,
        mediaSeconds = seconds,
        toySeconds = duration,
        toyAction = action,
        toyPower = power
    )

    private fun build(events: List<ParsedEvent>): Timeline = ToyTimeline.build(events)

    @Test
    fun `an active interval is start plus duration`() {
        val timeline = build(listOf(toyCommand(0, 10.0, 6.0)))
        val interval = timeline.toyIntervals.single()
        assertEquals(10.0, interval.startSeconds)
        assertEquals(16.0, interval.endSeconds)
    }

    /**
     * The FIFO drain: micro-tips of ~2 s each arrive faster than they execute, so the
     * commands chain back to back and the toy runs long after the last tip. This is
     * the tail worth cutting, and it must not be collapsed.
     */
    @Test
    fun `back-to-back commands fuse into one continuous block`() {
        val events = listOf(
            toyCommand(0, 100.0, 2.0),
            toyCommand(1, 102.0, 2.0),
            toyCommand(2, 104.0, 3.0),
            toyCommand(3, 107.0, 2.0)
        )
        val timeline = build(events)
        val interval = timeline.toyIntervals.single()
        assertEquals(100.0, interval.startSeconds)
        assertEquals(109.0, interval.endSeconds)
        assertEquals(9.0, timeline.stats.toyBusySeconds)
    }

    /**
     * FIFO: a command that arrives while another is still running must wait.
     *
     * This is the property the earlier implementation got wrong — it extended the
     * open block instead, which dropped the deferral and let the level lane emit
     * overlapping segments. Six back-to-back 10 s commands issued at t=0,0,0,0,0,0
     * therefore occupy 0..60 s, not 0..10 s.
     */
    @Test
    fun `an early command waits for the running one to finish`() {
        val events = (0 until 6).map { toyCommand(it, 0.0, 10.0, power = Power.LOW) }
        val timeline = build(events)
        val total = timeline.toyIntervals.sumOf { it.duration }
        assertEquals(60.0, total, "six 10 s commands must occupy 60 s of toy time")
        assertEquals(60.0, timeline.stats.toyBusySeconds)
    }

    /** The resulting intervals never overlap, whatever the arrival pattern. */
    @Test
    fun `queued commands produce non-overlapping intervals`() {
        val events = listOf(
            toyCommand(0, 100.0, 30.0, power = Power.LOW),
            toyCommand(1, 110.0, 30.0, power = Power.ULTRA),   // arrives 20 s early
            toyCommand(2, 115.0, 10.0, power = Power.MEDIUM),
            toyCommand(3, 400.0, 20.0, power = Power.HIGH)     // after a long idle
        )
        val timeline = build(events)
        // Level segments are the unmerged view, so overlap would show up here.
        val levels = timeline.levelSegments.sortedBy { it.startSeconds }
        for (i in 1 until levels.size) {
            assertTrue(
                levels[i].startSeconds >= levels[i - 1].endSeconds - 1e-6,
                "overlap: ${levels[i - 1]} then ${levels[i]}"
            )
        }
        // The first three chain back to back: 100 + 30 + 30 + 10 = 170.
        // The fourth runs at its own time, much later.
        val chained = levels.filter { it.startSeconds < 200 }
        assertEquals(100.0, chained.first().startSeconds)
        assertEquals(170.0, chained.maxOf { it.endSeconds })
        assertTrue(levels.any { it.startSeconds >= 400.0 }, "the later command keeps its own time")
    }

    /** A stop drains the queue, so a pending command never runs. */
    @Test
    fun `a stop cancels what is still queued`() {
        val events = listOf(
            toyCommand(0, 100.0, 30.0),
            toyCommand(1, 105.0, 30.0),                       // queued behind the first
            toyCommand(2, 110.0, 0.0, action = "clear")       // empties the queue at 110
        )
        val timeline = build(events)
        assertEquals(110.0, timeline.toyIntervals.single().endSeconds, "queue must be cut at the stop")
        assertEquals(10.0, timeline.stats.toyBusySeconds)
    }

    @Test
    fun `a genuine idle gap is preserved`() {
        // Real idle gaps run far above the 3 s display merge (p75 ~= 24 s).
        val timeline = build(listOf(toyCommand(0, 100.0, 5.0), toyCommand(1, 130.0, 5.0)))
        assertEquals(2, timeline.toyIntervals.size)
        assertEquals(100.0, timeline.toyIntervals[0].startSeconds)
        assertEquals(130.0, timeline.toyIntervals[1].startSeconds)
    }

    @Test
    fun `a gap below the display threshold is bridged for display`() {
        // 2 s apart with 1 s of runtime: a lull inside one burst, not a stop.
        val timeline = build(listOf(toyCommand(0, 100.0, 1.0), toyCommand(1, 102.0, 1.0)))
        val interval = timeline.toyIntervals.single()
        assertEquals(100.0, interval.startSeconds)
        assertEquals(103.0, interval.endSeconds)
    }

    @Test
    fun `a stop command truncates the running interval`() {
        val events = listOf(
            toyCommand(0, 100.0, 60.0),
            toyCommand(1, 120.0, 0.0, action = "clear")
        )
        val timeline = build(events)
        val interval = timeline.toyIntervals.single()
        assertEquals(100.0, interval.startSeconds)
        assertEquals(120.0, interval.endSeconds, "clear must cut the block at its own time")
        assertTrue(timeline.toySpecials.any { it.action == "clear" })
    }

    @Test
    fun `a named action without a duration becomes a marker, not a block`() {
        val timeline = build(listOf(toyCommand(0, 50.0, 0.0, action = "earthquake")))
        assertTrue(timeline.toyIntervals.isEmpty())
        assertEquals("earthquake", timeline.toySpecials.single().action)
    }

    /**
     * Two overlapping commands now queue instead of merging.
     *
     * The runtime lane still shows one continuous block (that is what the display
     * merge is for), but the level lane must keep them apart so each command's own
     * intensity lands on the stretch it actually ran.
     */
    @Test
    fun `overlapping commands queue and keep their own power`() {
        val timeline = build(
            listOf(
                toyCommand(0, 10.0, 10.0, power = Power.LOW),
                toyCommand(1, 12.0, 10.0, power = Power.ULTRA)
            )
        )
        // Runtime: one glued block spanning both.
        assertEquals(30.0, timeline.toyIntervals.single().endSeconds)
        // Level: two segments, in order, non-overlapping, each with its own power.
        val levels = timeline.levelSegments.sortedBy { it.startSeconds }
        assertEquals(2, levels.size)
        assertEquals(Power.LOW, levels[0].power)
        assertEquals(10.0, levels[0].startSeconds)
        assertEquals(20.0, levels[0].endSeconds)
        assertEquals(Power.ULTRA, levels[1].power)
        assertEquals(20.0, levels[1].startSeconds)
        assertEquals(30.0, levels[1].endSeconds)
    }

    @Test
    fun `untimed events are dropped from the lanes`() {
        val untimed = ParsedEvent(
            lineIndex = 0,
            kind = EventKind.TOY_CMD,
            channel = "newChatMessage",
            ownEpochMs = null,
            mediaSeconds = null,
            toySeconds = 10.0
        )
        val timeline = build(listOf(untimed, toyCommand(1, 5.0, 3.0)))
        assertEquals(1, timeline.toyIntervals.size)
        assertEquals(5.0, timeline.toyIntervals.single().startSeconds)
    }

    @Test
    fun `gifts goals and chats land on their own lanes`() {
        val events = listOf(
            ParsedEvent(0, EventKind.TIP, "newChatMessage", 0L, mediaSeconds = 1.0, amount = 88L),
            ParsedEvent(1, EventKind.TIP, "newChatMessage", 0L, mediaSeconds = 2.0, amount = 12L),
            ParsedEvent(2, EventKind.CHAT, "newChatMessage", 0L, mediaSeconds = 3.0),
            ParsedEvent(
                3, EventKind.GOAL, "goalChanged", 0L, mediaSeconds = 4.0,
                goal = github.rikacelery.cutter.events.GoalInfo(1000, 250, 750, "goal", true)
            )
        )
        val timeline = build(events)
        assertEquals(2, timeline.gifts.size)
        assertEquals(100L, timeline.stats.tipTotal)
        assertEquals(1, timeline.chats.size)
        assertEquals(1, timeline.goals.size)
        assertEquals(4.0, timeline.goals.single().seconds)
    }

    /** End-to-end: real payload lines, real start stamp, real offsets. */
    @Test
    fun `parses and places a real command sequence`() {
        val lines = listOf(
            """{"push":{"channel":"newChatMessage@1","pub":{"data":{"message":{
               "createdAt":"2026-03-18T11:59:36Z","type":"lovense","details":{"lovenseDetails":
               {"detail":{"time":"3","power":"low"}}}}}}}}""".replace("\n", ""),
            // No timestamp: interpolated between its neighbours by line index.
            """{"push":{"channel":"goalChanged@1","pub":{"data":{"goal":{"goal":100,"spent":10}}}}}""",
            """{"push":{"channel":"newChatMessage@1","pub":{"data":{"message":{
               "createdAt":"2026-03-18T11:59:46Z","type":"lovense","details":{"lovenseDetails":
               {"detail":{"time":"4","power":"ultraHigh"}}}}}}}}""".replace("\n", "")
        )
        val events = EventParser.parse(lines.asSequence())
        assertEquals(3, events.size)

        val start = EventParser.parseInstant("2026-03-18T11:59:36Z")!! / 1000
        val timeline = ToyTimeline.build(events, start)

        assertEquals(2, timeline.toyIntervals.size)
        assertEquals(0.0, timeline.toyIntervals[0].startSeconds)
        assertEquals(10.0, timeline.toyIntervals[1].startSeconds)
        // Line 1 sits half way between line 0 and line 2 in both index and time.
        assertEquals(5.0, timeline.goals.single().seconds)
        assertEquals(Power.LOW, timeline.toyIntervals[0].power)
        assertEquals(Power.ULTRA, timeline.toyIntervals[1].power)
    }
}
