package github.rikacelery.cutter

import github.rikacelery.cutter.events.DEFAULT_EVENT_NUDGE_SECONDS
import github.rikacelery.cutter.events.EventKind
import github.rikacelery.cutter.events.EventParser
import github.rikacelery.cutter.events.EventTimeMapper
import github.rikacelery.cutter.events.TimeConfidence
import github.rikacelery.cutter.media.Power
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parser tests built from payloads captured verbatim out of the production corpus.
 *
 * The timestamp rule gets its own test because getting it wrong is silent and huge:
 * a naive scan for ISO-8601 strings picks up `expiredAt` deadlines and moves events
 * by up to a day (measured worst case 85920 s).
 */
class EventParserTest {

    /** Real `message.type=lovense` command, including the FIFO duration and power. */
    private val lovenseCommand = """
        {"push":{"channel":"newChatMessage@208569547","pub":{"data":{"additionalData":{},
        "message":{"cacheId":"x","createdAt":"2026-03-18T11:59:36Z","type":"lovense",
        "details":{"lovenseDetails":{"detail":{"amount":"59","name":"Desmondd10",
        "power":"medium","time":"6","specialActualValue":"giveControl"}}},
        "userData":{"username":"_Freydis_"}}}}}}
    """.trimIndent().replace("\n", "")

    private val chatMessage = """
        {"push":{"channel":"newChatMessage@123","pub":{"data":{"message":{
        "createdAt":"2026-03-18T11:59:36Z","type":"text","details":{"body":"我生氣了"},
        "userData":{"username":"neno540"}}}}}}
    """.trimIndent().replace("\n", "")

    private val tipMessage = """
        {"push":{"channel":"newChatMessage@123","pub":{"data":{"message":{
        "createdAt":"2026-08-20T10:40:00Z","type":"tip","details":{"amount":88,
        "source":"interactiveToy","tipData":{"triggerType":"basicLevel"}},
        "userData":{"username":"UZU96"}}}}}}
    """.trimIndent().replace("\n", "")

    /** Carries no timestamp at all — the common case for the interesting signals. */
    private val goalChanged = """
        {"push":{"channel":"goalChanged@123","pub":{"data":{"additionalData":{},
        "goal":{"description":"口红震阴蒂","goal":3999,"isEnabled":true,"left":2682,"spent":1317}}}}}
    """.trimIndent().replace("\n", "")

    /** Has BOTH `createdAt` and a `+1 day` `expiredAt` deadline. */
    private val userBanned = """
        {"push":{"channel":"userBanned@123","pub":{"data":{"ban":{"bannedId":248658408,
        "createdAt":"2026-03-21T18:27:44Z","expiredAt":"2026-03-22T18:27:44Z",
        "type":"mute"},"banReason":null}}}}
    """.trimIndent().replace("\n", "")

    private val groupShow = """
        {"push":{"channel":"groupShow@123","pub":{"data":{"groupId":136108203,
        "showId":158531338,"startedAt":"2026-05-16T15:47:02Z","state":"start","topic":"cum"}}}}
    """.trimIndent().replace("\n", "")

    @Test
    fun `unwraps the push envelope, the type-data form and a bare payload`() {
        val pushed = assertNotNull(EventParser.parseLine(chatMessage, 0))
        assertEquals(EventKind.CHAT, pushed.kind)
        assertEquals("newChatMessage", pushed.channel)
        assertEquals("我生氣了", pushed.text)
        assertEquals("neno540", pushed.user)

        val typed = assertNotNull(
            EventParser.parseLine("""{"type":"goalChanged","data":{"goal":{"goal":10}}}""", 0)
        )
        assertEquals(EventKind.GOAL, typed.kind)

        val bare = assertNotNull(
            EventParser.parseLine("""{"additionalData":{},"settings":{},"type":"lovense"}""", 0)
        )
        assertEquals("lovense", bare.channel)
    }

    @Test
    fun `extracts every toy command field`() {
        val event = assertNotNull(EventParser.parseLine(lovenseCommand, 0))
        assertEquals(EventKind.TOY_CMD, event.kind)
        assertEquals(6.0, event.toySeconds)
        assertEquals(Power.MEDIUM, event.toyPower)
        assertEquals("giveControl", event.toyAction)
        assertEquals(59L, event.amount)
        assertEquals("Desmondd10", event.user)
        assertEquals(1773835176000L, event.ownEpochMs)
    }

    @Test
    fun `extracts tip fields`() {
        val event = assertNotNull(EventParser.parseLine(tipMessage, 0))
        assertEquals(EventKind.TIP, event.kind)
        assertEquals(88L, event.amount)
        assertEquals("interactiveToy", event.tipSource)
        assertEquals("basicLevel", event.triggerType)
    }

    @Test
    fun `goal events without a timestamp still carry their payload`() {
        val event = assertNotNull(EventParser.parseLine(goalChanged, 0))
        assertEquals(EventKind.GOAL, event.kind)
        assertNull(event.ownEpochMs)
        val goal = assertNotNull(event.goal)
        assertEquals(3999L, goal.goal)
        assertEquals(1317L, goal.spent)
        assertEquals(2682L, goal.left)
        assertEquals("口红震阴蒂", goal.description)
    }

    @Test
    fun `groupShow carries startedAt`() {
        val event = assertNotNull(EventParser.parseLine(groupShow, 0))
        assertEquals(EventKind.SHOW, event.kind)
        assertEquals("start", event.showState)
        assertEquals(1778946422000L, event.ownEpochMs)
    }

    /**
     * A ban carries two tempting timestamps and neither is the event's own time:
     * `expiredAt` is a deadline a day ahead, and `ban.createdAt` is when the ban was
     * *originally issued*. The corpus contains a re-broadcast ban whose `createdAt`
     * is 124 days before the stream; trusting it dragged events in that recording
     * back by four months. A ban therefore has no own timestamp and is interpolated.
     */
    @Test
    fun `ban timestamps are never trusted as the event time`() {
        val event = assertNotNull(EventParser.parseLine(userBanned, 0))
        assertEquals(EventKind.BAN, event.kind)
        assertNull(event.ownEpochMs)

        val staleBan = """
            {"push":{"channel":"userBanned@123","pub":{"data":{"ban":{
            "bannedId":1,"createdAt":"2025-11-19T10:11:39Z","expiredAt":"2025-11-20T10:11:39Z",
            "type":"mute"}}}}}
        """.trimIndent().replace("\n", "")
        assertNull(assertNotNull(EventParser.parseLine(staleBan, 0)).ownEpochMs)
    }

    @Test
    fun `a toy settings broadcast is not a command`() {
        val settings = """
            {"push":{"channel":"interactiveToyStatusChanged@123","pub":{"data":{
            "settings":{"levels":{"level1":{"max":9,"min":2,"time":"5","vLevel":5}}},
            "status":{"isEnabled":true}}}}}
        """.trimIndent().replace("\n", "")
        val event = assertNotNull(EventParser.parseLine(settings, 0))
        assertEquals(EventKind.TOY_SETTINGS, event.kind)
        assertNull(event.toySeconds)
    }

    @Test
    fun `clear and pause are stop commands`() {
        for (action in listOf("clear", "pause")) {
            val line = """{"push":{"channel":"newChatMessage@1","pub":{"data":{"message":{
                "createdAt":"2026-03-18T11:59:36Z","type":"lovense","details":{"lovenseDetails":
                {"detail":{"time":"0","specialActualValue":"$action"}}}}}}}}"""
            val event = assertNotNull(EventParser.parseLine(line, 0))
            assertTrue(event.isToyStop, "$action should be a stop command")
        }
    }

    @Test
    fun `unparseable lines are skipped rather than failing the file`() {
        val events = EventParser.parse(listOf("not json", "", chatMessage, "{broken").asSequence())
        assertEquals(1, events.size)
    }
}

/** Time mapping: the anchors, the interpolation and the degenerate cases. */
class EventTimeMapperTest {

    private fun event(line: Int, epochMs: Long?) = github.rikacelery.cutter.events.ParsedEvent(
        lineIndex = line,
        kind = EventKind.CHAT,
        channel = "newChatMessage",
        ownEpochMs = epochMs
    )

    /**
     * An arbitrary but realistic recording-start epoch, used as the origin every
     * event is measured against. 1773835176 == 2026-03-18T11:59:36Z.
     */
    private val start = 1773835176L

    @Test
    fun `an event with its own stamp is exact`() {
        val mapped = EventTimeMapper(start, nudgeSeconds = 0.0).map(listOf(event(0, start * 1000)))
        assertEquals(0.0, mapped.single().mediaSeconds)
        assertEquals(TimeConfidence.EXACT, mapped.single().confidence)
    }

    @Test
    fun `an event without a stamp is interpolated between its neighbours`() {
        val events = listOf(
            event(0, start * 1000),
            event(10, null),
            event(20, (start + 20) * 1000)
        )
        val mapped = EventTimeMapper(start, nudgeSeconds = 0.0).map(events)
        assertEquals(TimeConfidence.EXACT, mapped[0].confidence)
        assertEquals(TimeConfidence.INTERPOLATED, mapped[1].confidence)
        // Half way in line index between 0 s and 20 s.
        assertEquals(10.0, mapped[1].mediaSeconds)
        assertEquals(20.0, mapped[2].mediaSeconds)
    }

    @Test
    fun `the nudge shifts every event`() {
        val mapped = EventTimeMapper(start, nudgeSeconds = -2.5).map(listOf(event(0, start * 1000)))
        assertEquals(-2.5, mapped.single().mediaSeconds)
    }

    /**
     * The offset is calibrated against the panel overlay rather than guessed, so the number
     * itself is part of the contract: four independent anchors (+7.0 s, spanning 19 min to
     * 8.6 h inside one recording and repeating on another) all agreed. Changing it without
     * redoing that measurement would silently misalign every event lane — the exact class of
     * failure this whole investigation was about.
     */
    @Test
    fun `the calibrated default offset is +7 seconds`() {
        assertEquals(7.0, DEFAULT_EVENT_NUDGE_SECONDS)
        val mapped = EventTimeMapper(start).map(listOf(event(0, start * 1000)))
        assertEquals(7.0, mapped.single().mediaSeconds)
    }

    /**
     * A recording with no parseable start treats its first stamped event as the origin. The
     * offset still applies — it comes from how the file-name stamp is written, not from the
     * anchor — so that first event lands at +7 s rather than at 0.
     */
    @Test
    fun `the default offset also applies without a recording start`() {
        val mapped = EventTimeMapper(null).map(listOf(event(0, start * 1000)))
        assertEquals(DEFAULT_EVENT_NUDGE_SECONDS, mapped.single().mediaSeconds)
    }

    @Test
    fun `events outside the anchor range are extrapolated, not piled onto the anchor`() {
        val events = listOf(
            event(0, start * 1000),
            event(100, (start + 100) * 1000),
            event(200, null)
        )
        val mapped = EventTimeMapper(start, nudgeSeconds = 0.0).map(events)
        assertEquals(TimeConfidence.EXTRAPOLATED, mapped[2].confidence)
        // The anchor span runs 1 s per line, so line 200 lands 100 s past line 100.
        assertEquals(200.0, mapped[2].mediaSeconds)
    }

    /**
     * Real recording `fox-yiyi-2026_03_19_19_57_24`: 48 configuration events precede
     * the first chat, whose stamp is 639 s in. Backward extrapolation at the anchor
     * span's rate put those events 18 minutes before the start of the media. An
     * estimate must never land outside the timeline.
     */
    @Test
    fun `an untimed event before the first anchor is never placed before zero`() {
        val events = listOf(
            event(0, null),
            event(47, null),
            event(48, (start + 639) * 1000),
            event(49, (start + 660) * 1000)
        )
        val mapped = EventTimeMapper(start, nudgeSeconds = 0.0).map(events)
        assertTrue(mapped.take(2).all { (it.mediaSeconds ?: -1.0) >= 0.0 },
            "estimates must be pinned to the start of the media, got ${mapped.take(2).map { it.mediaSeconds }}")
        assertEquals(TimeConfidence.EXTRAPOLATED, mapped[0].confidence)
        assertEquals(639.0, mapped[2].mediaSeconds)
    }

    /** Forward drift past the last anchor is capped rather than unbounded. */
    @Test
    fun `forward extrapolation is bounded`() {
        val events = listOf(
            event(0, start * 1000),
            event(10, (start + 10) * 1000),
            // A very dense anchor span would otherwise imply ~1000 s per line here.
            event(1000, null)
        )
        val mapped = EventTimeMapper(start, nudgeSeconds = 0.0).map(events)
        val tail = mapped[2].mediaSeconds!!
        assertTrue(tail <= 10.0 + 120.0, "tail estimate should be capped, got $tail")
    }

    @Test
    fun `a file with no timestamps yields no times`() {
        val mapped = EventTimeMapper(start, nudgeSeconds = 0.0).map(listOf(event(0, null), event(1, null)))
        assertTrue(mapped.all { it.mediaSeconds == null })
        assertTrue(mapped.all { it.confidence == TimeConfidence.NONE })
    }

    /**
     * Defence in depth for unknown payloads: even if a stale occurrence time slips
     * through extraction, the append-ordering guard must reject it. Real stale values
     * are days away; the tolerance is a minute.
     */
    @Test
    fun `a timestamp that breaks append ordering is dropped`() {
        val staleDays = 124L * 24 * 3600
        val events = listOf(
            event(0, start * 1000),
            // 124 days in the past, right in the middle of a monotonic sequence.
            event(1, (start - staleDays) * 1000),
            event(2, (start + 10) * 1000),
            event(3, null)
        )
        val mapper = EventTimeMapper(start, nudgeSeconds = 0.0)
        val mapped = mapper.map(events)
        assertEquals(1, mapper.droppedStaleAnchors)
        // The stale event falls back to interpolation instead of anchoring.
        assertEquals(TimeConfidence.INTERPOLATED, mapped[1].confidence)
        assertEquals(5.0, mapped[1].mediaSeconds)
        assertEquals(0.0, mapped[0].mediaSeconds)
        assertEquals(10.0, mapped[2].mediaSeconds)
    }

    @Test
    fun `without a recording start the first event becomes the origin`() {
        val mapped = EventTimeMapper(null, nudgeSeconds = 0.0).map(listOf(event(0, start * 1000), event(5, (start + 7) * 1000)))
        assertEquals(0.0, mapped[0].mediaSeconds)
        assertEquals(7.0, mapped[1].mediaSeconds)
    }
}
