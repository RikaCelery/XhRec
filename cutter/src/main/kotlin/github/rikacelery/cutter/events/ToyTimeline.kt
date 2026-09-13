package github.rikacelery.cutter.events

import github.rikacelery.cutter.media.Power

/**
 * Builds the timeline lanes from parsed events.
 *
 * ## FIFO is already encoded in the data
 *
 * Toy commands queue: when several gifts land at once the toy runs them in order,
 * so the toy keeps going well after the last tip. We do **not** simulate that queue,
 * because the event stream already reflects it: a `lovense` message's `createdAt` is
 * the moment the command is *dequeued and executed*, not when the gift arrived.
 * Measured over six toy-heavy recordings, **58% of consecutive commands satisfy
 * `next.createdAt ≈ prev.createdAt + prev.time`** (±1 s) — exact back-to-back
 * chaining — while the remaining gaps are genuine idle (35% exceed 5 s, 23% exceed
 * 30 s).
 *
 * So an active interval is simply `[t, t + detail.time]`, and the FIFO backlog tail
 * after the last tip falls out for free. That tail is usually the most interesting
 * part of a recording, which is exactly why it must not be computed away.
 *
 * `clear`/`pause` stop whatever is running, so they truncate the open interval.
 */
object ToyTimeline {

    /**
     * Merge toy intervals separated by less than this for *display*, so a burst of
     * 2-second micro-commands reads as one continuous block. Real idle gaps are far
     * larger (p75 ≈ 24 s), so this never bridges them.
     */
    const val DEFAULT_DISPLAY_GAP = 3.0

    /** Commands closer together than this are treated as one continuous run. */
    private const val POWER_FUSE_GAP = 0.05

    fun build(events: List<ParsedEvent>): Timeline {
        val timed = events.filter { it.mediaSeconds != null }
        val ordered = timed.sortedBy { it.mediaSeconds }

        val rawIntervals = ArrayList<ToyInterval>()
        val specials = ArrayList<SpecialMarker>()
        val gifts = ArrayList<GiftMarker>()
        val goals = ArrayList<GoalMarker>()
        val chats = ArrayList<Double>()

        /**
         * When the toy next becomes free.
         *
         * Commands are **queued, never pre-empted**: one that arrives while another
         * is still running waits for it to finish. The queue drains in arrival order,
         * so a command's effective start is `max(its own time, when the toy frees
         * up)` and intervals can never overlap.
         *
         * The previous version did the opposite — an early command extended the open
         * block and the block kept the *stronger* of the two powers. That silently
         * discarded the deferral (a burst of gifts running past its last tip showed
         * the wrong end time) and mis-attributed intensity, and it let the level lane
         * emit overlapping segments.
         */
        var cursor = 0.0

        for (event in ordered) {
            val t = event.mediaSeconds ?: continue
            when (event.kind) {
                EventKind.TOY_CMD -> {
                    val action = event.toyAction
                    if (event.isToyStop) {
                        // A stop empties the queue: whatever is running is cut short and
                        // anything still pending never runs.
                        // Everything still queued never runs, so drop it first; only
                        // then cut whatever is actually on the toy at `t`. Truncating
                        // merely the last entry would leave an early command running
                        // past the stop and keep a stale queued entry behind it.
                        rawIntervals.removeAll { it.startSeconds >= t }
                        rawIntervals.lastOrNull()?.let { last ->
                            if (last.endSeconds > t) {
                                rawIntervals[rawIntervals.size - 1] = last.copy(endSeconds = t)
                            }
                        }
                        cursor = t
                        specials.add(SpecialMarker(t, action ?: "stop", event.user, event.amount))
                        continue
                    }
                    val duration = event.toySeconds ?: 0.0
                    if (duration <= 0.0) {
                        // A named action with no duration is a marker, not a block.
                        action?.let { specials.add(SpecialMarker(t, it, event.user, event.amount)) }
                        continue
                    }
                    // FIFO: start when the toy is free, not when the command arrived.
                    val start = maxOf(t, cursor)
                    val end = start + duration
                    cursor = end
                    rawIntervals.add(ToyInterval(start, end, event.toyPower, action, event.user, event.amount))
                    if (action != null) {
                        // The action happens when it is dequeued, so the marker follows
                        // the executed position rather than the arrival position.
                        specials.add(SpecialMarker(start, action, event.user, event.amount))
                    }
                }

                EventKind.TIP -> {
                    val amount = event.amount ?: 0L
                    gifts.add(GiftMarker(t, amount, event.tipSource, event.user, event.triggerType))
                }

                EventKind.GOAL -> {
                    val goal = event.goal
                    if (goal != null) {
                        goals.add(GoalMarker(t, goal.goal, goal.spent, goal.description))
                    }
                }

                EventKind.SHOW -> {
                    gifts.add(GiftMarker(t, 0L, "show:${event.showState ?: "?"}", event.user, null))
                }

                EventKind.KING -> {
                    gifts.add(GiftMarker(t, event.amount ?: 0L, "king", event.user, null))
                }

                EventKind.CHAT -> chats.add(t)

                else -> Unit
            }
        }
        // An event file can start a few seconds before the recording does (the event
        // subscription opens first, and the file-name stamp is truncated to whole
        // seconds). A command issued just before zero may still be running inside the
        // recording, so clamp the start rather than discarding the interval.
        val clipped = rawIntervals.mapNotNull { interval ->
            val start = interval.startSeconds.coerceAtLeast(0.0)
            val end = interval.endSeconds
            if (end <= 0.0 || end - start <= 1e-6) null else interval.copy(startSeconds = start)
        }

        val merged = mergeIntervals(clipped, DEFAULT_DISPLAY_GAP)
        return Timeline(
            toyIntervals = merged,
            levelSegments = fuseEqualPower(clipped),
            toySpecials = specials.map { it.copy(seconds = it.seconds.coerceAtLeast(0.0)) },
            gifts = gifts.map { it.copy(seconds = it.seconds.coerceAtLeast(0.0)) },
            goals = goals.map { it.copy(seconds = it.seconds.coerceAtLeast(0.0)) },
            chats = chats.map { it.coerceAtLeast(0.0) },
            events = ordered,
            stats = statsOf(events, ordered, merged)
        )
    }

    /**
     * Fuse only contiguously-running commands of the *same* power.
     *
     * This shrinks a long run of identical micro-commands without flattening the
     * intensity curve the way a gap merge would.
     */
    fun fuseEqualPower(intervals: List<ToyInterval>): List<ToyInterval> {
        if (intervals.isEmpty()) return emptyList()
        val sorted = intervals.sortedBy { it.startSeconds }
        val out = ArrayList<ToyInterval>(sorted.size)
        var current = sorted.first()
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            val touching = next.startSeconds - current.endSeconds <= POWER_FUSE_GAP
            if (touching && next.power == current.power) {
                current = current.copy(
                    endSeconds = maxOf(current.endSeconds, next.endSeconds),
                    amount = maxOf(current.amount ?: 0L, next.amount ?: 0L)
                )
            } else {
                out.add(current)
                current = next
            }
        }
        out.add(current)
        return out
    }

    /**
     * Union overlapping intervals and bridge gaps smaller than [gapSeconds].
     * Input must already be sorted by start.
     */
    fun mergeIntervals(intervals: List<ToyInterval>, gapSeconds: Double): List<ToyInterval> {
        if (intervals.isEmpty()) return emptyList()
        val sorted = intervals.sortedBy { it.startSeconds }
        val out = ArrayList<ToyInterval>(sorted.size)
        var current = sorted.first()
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.startSeconds - current.endSeconds <= gapSeconds) {
                current = current.copy(
                    endSeconds = maxOf(current.endSeconds, next.endSeconds),
                    power = maxOfPower(current.power, next.power),
                    action = current.action ?: next.action
                )
            } else {
                out.add(current)
                current = next
            }
        }
        out.add(current)
        return out
    }

    private fun maxOfPower(a: Power, b: Power): Power =
        if (a.ordinalLevel >= b.ordinalLevel) a else b

    private fun statsOf(
        all: List<ParsedEvent>,
        ordered: List<ParsedEvent>,
        merged: List<ToyInterval>
    ): EventStats {
        val toyCommands = ordered.filter { it.kind == EventKind.TOY_CMD }
        val tips = ordered.filter { it.kind == EventKind.TIP }
        val confidences = ordered.map { it.confidence }
        val overall = when {
            confidences.isEmpty() -> TimeConfidence.NONE
            confidences.any { it == TimeConfidence.EXACT } -> TimeConfidence.EXACT
            confidences.any { it == TimeConfidence.INTERPOLATED } -> TimeConfidence.INTERPOLATED
            confidences.any { it == TimeConfidence.EXTRAPOLATED } -> TimeConfidence.EXTRAPOLATED
            else -> TimeConfidence.NONE
        }
        return EventStats(
            totalLines = all.size,
            unparseableLines = 0,
            withOwnTimestamp = all.count { it.ownEpochMs != null },
            toyCommandCount = toyCommands.size,
            toySecondsSum = toyCommands.sumOf { it.toySeconds ?: 0.0 },
            toyBusySeconds = merged.sumOf { it.duration },
            specialCommandCount = toyCommands.count { it.toyAction != null },
            tipCount = tips.size,
            tipTotal = tips.sumOf { it.amount ?: 0L },
            chatCount = ordered.count { it.kind == EventKind.CHAT },
            goalCount = ordered.count { it.kind == EventKind.GOAL },
            showCount = ordered.count { it.kind == EventKind.SHOW },
            maxPower = merged.maxByOrNull { it.power.ordinalLevel }?.power ?: Power.NONE,
            firstEventSeconds = ordered.firstOrNull()?.mediaSeconds,
            lastEventSeconds = ordered.lastOrNull()?.mediaSeconds,
            confidence = overall
        )
    }

    /** Maps a parsed event list onto the media timeline and builds the lanes. */
    fun build(
        events: List<ParsedEvent>,
        recordingStartEpochSeconds: Long?,
        nudgeSeconds: Double = 0.0
    ): Timeline = build(EventTimeMapper(recordingStartEpochSeconds, nudgeSeconds).map(events))
}
