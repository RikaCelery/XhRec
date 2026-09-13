package github.rikacelery.cutter.suggest

import github.rikacelery.cutter.events.EventKind
import github.rikacelery.cutter.events.ParsedEvent
import github.rikacelery.cutter.events.Timeline
import github.rikacelery.cutter.media.Power
import kotlinx.serialization.Serializable

/**
 * Tuning for the suggestion pipeline. Every value is exposed to the UI so the user can
 * drag a slider and watch blocks glue together or split apart, which is the direct
 * answer to "avoid jitter, glue the holes".
 */
@Serializable
data class SuggestPrefs(
    /** Seconds of lead-in added before each raw candidate: the reaction precedes the gift. */
    val padBefore: Double = 8.0,
    /** Seconds of tail added after each candidate. */
    val padAfter: Double = 6.0,
    /**
     * Gaps shorter than this are glued shut.
     *
     * Chained toy commands sit 0–1 s apart, while genuine idle gaps are far larger
     * (measured p75 ≈ 24 s, with 23% above 30 s), so ~15 s fuses bursts without
     * swallowing real breaks.
     */
    val mergeGap: Double = 15.0,
    /** Blocks shorter than this are jitter, not content. */
    val minBlock: Double = 45.0,
    /** A short block closer than this to a neighbour is absorbed instead of dropped. */
    val absorbGap: Double = 45.0,
    /** Blocks longer than this are split at their quietest point. */
    val maxBlock: Double = 600.0,
    /** Blocks scoring below this are discarded. */
    val minScore: Double = 0.35,
    val maxSuggestions: Int = 50,
    val weightToy: Double = 1.8,
    val weightSpecial: Double = 2.5,
    val weightTip: Double = 1.0,
    val weightShow: Double = 3.0,
    val weightKing: Double = 2.5,
    val weightGoal: Double = 2.0,
    val weightChat: Double = 0.3,
    /**
     * Pure rule: the only thing that creates a candidate is the toy *running*.
     *
     * Gifts, chat, goals and named special commands are ignored entirely — no block
     * of their own, and no influence on ranking. Special commands that carry a
     * duration still count, because they show up as toy runtime; the zero-length
     * markers do not. Useful when the runtime itself is the signal of interest and
     * every other event type is noise.
     */
    val toyOnly: Boolean = false,
    /**
     * Whether a gift that *triggered* a toy command may score twice.
     *
     * It is one real-world action recorded as two events: a `tip` with
     * `source=interactiveToy` and the `lovense` command it produced. Measured on a
     * real recording, 169 of 393 tips are of that kind and 132 pair with a toy
     * command within 5 s. Scoring both the amount and the runtime it caused inflates
     * those moments against gifts that triggered nothing.
     *
     * Off by default: the runtime already represents the action, so only the amount
     * of *non-toy* gifts is scored. The full total is still reported for context.
     */
    val scoreToyTips: Boolean = false
) {
    companion object {
        val PRESETS: Map<String, SuggestPrefs> = mapOf(
            "toy" to SuggestPrefs(),
            "show" to SuggestPrefs(weightToy = 0.8, weightTip = 1.5, weightShow = 4.0, mergeGap = 20.0),
            "chatty" to SuggestPrefs(weightChat = 1.2, weightTip = 0.6, weightToy = 1.0, mergeGap = 10.0),
            "toypure" to SuggestPrefs(toyOnly = true),
            "everything" to SuggestPrefs(
                mergeGap = 10.0, minBlock = 20.0, minScore = 0.1, weightChat = 0.8
            )
        )
    }
}

/** A signal that contributed to a suggestion, kept so the UI can explain itself. */
@Serializable
data class Reason(
    val kind: String,
    val at: Double,
    val detail: String,
    val weight: Double
)

@Serializable
data class SuggestedBlock(
    val start: Double,
    val end: Double,
    val score: Double,
    val toySeconds: Double,
    val tipTotal: Long,
    val specialCount: Int,
    val peakPower: Int,
    val reasons: List<Reason>
)

/**
 * Turns the event timeline into candidate cut points.
 *
 * The pipeline is deliberately staged — dilate, glue, de-jitter, score, cap — because
 * the raw signal is unusable on its own: the median toy command lasts **2 seconds** and
 * micro-gifts of a single token dominate the corpus, so a naive one-cut-per-event rule
 * produces thousands of 2-second fragments. Equally, treating each burst as separate
 * would cut a continuously-running stretch into pieces. Each stage is separately
 * parameterised so the trade-off is visible and adjustable rather than baked in.
 */
object CutSuggester {

    fun suggest(
        timeline: Timeline,
        durationSeconds: Double,
        prefs: SuggestPrefs = SuggestPrefs()
    ): List<SuggestedBlock> {
        if (durationSeconds <= 0) return emptyList()

        val raw = rawIntervals(timeline, prefs, durationSeconds)
        if (raw.isEmpty()) return emptyList()

        val dilated = raw.map {
            Interval(
                (it.start - prefs.padBefore).coerceAtLeast(0.0),
                (it.end + prefs.padAfter).coerceAtMost(durationSeconds),
                it.reasons
            )
        }

        val glued = glue(dilated, prefs.mergeGap)
        val deJittered = deJitter(glued, prefs)
        val capped = deJittered.flatMap { splitIfTooLong(it, prefs, timeline) }

        return capped
            .map { scored(it, timeline, prefs) }
            .filter { it.score >= prefs.minScore && it.end - it.start > 0 }
            .sortedByDescending { it.score }
            .take(prefs.maxSuggestions)
            .sortedBy { it.start }
    }

    internal data class Interval(val start: Double, val end: Double, val reasons: List<Reason>)

    /** One interval per contributing signal, before any merging. */
    private fun rawIntervals(timeline: Timeline, prefs: SuggestPrefs, duration: Double): List<Interval> {
        val out = ArrayList<Interval>(timeline.toyIntervals.size + 64)
        for (interval in timeline.toyIntervals) {
            out.add(
                Interval(
                    interval.startSeconds, interval.endSeconds,
                    listOf(
                        Reason(
                            kind = "TOY",
                            at = interval.startSeconds,
                            detail = buildString {
                                append("玩具运行 ").append(Math.round(interval.duration)).append("s")
                                if (interval.power != Power.NONE) append(" · ").append(interval.power.label)
                                interval.action?.let { append(" · ").append(it) }
                            },
                            weight = prefs.weightToy
                        )
                    )
                )
            )
        }
        // Pure toy rule: runtime is the only candidate source, so stop here.
        if (prefs.toyOnly) return out.filter { it.start <= duration }

        for (special in timeline.toySpecials) {
            out.add(
                Interval(
                    special.seconds, special.seconds,
                    listOf(
                        Reason(
                            "SPECIAL", special.seconds,
                            "特殊命令 ${special.action}${special.user?.let { " · $it" } ?: ""}",
                            prefs.weightSpecial
                        )
                    )
                )
            )
        }
        for (gift in timeline.gifts) {
            val isShow = gift.source?.startsWith("show:") == true
            val isKing = gift.source == "king"
            out.add(
                Interval(
                    gift.seconds, gift.seconds,
                    listOf(
                        Reason(
                            if (isShow) "SHOW" else if (isKing) "KING" else "TIP",
                            gift.seconds,
                            when {
                                isShow -> "场次 ${gift.source.removePrefix("show:")}"
                                isKing -> "newKing ${gift.amount}"
                                else -> "礼物 ${gift.amount}${gift.source?.let { " · $it" } ?: ""}"
                            },
                            when {
                                isShow -> prefs.weightShow
                                isKing -> prefs.weightKing
                                else -> prefs.weightTip
                            }
                        )
                    )
                )
            )
        }
        for (goal in timeline.goals) {
            out.add(
                Interval(
                    goal.seconds, goal.seconds,
                    listOf(Reason("GOAL", goal.seconds, "目标 ${goal.spent ?: 0}/${goal.goal ?: 0}", prefs.weightGoal))
                )
            )
        }
        // Chat is only interesting in aggregate, so it contributes dense clusters
        // rather than a candidate per message.
        out.addAll(chatClusters(timeline, prefs))
        return out.filter { it.start <= duration }
    }

    /**
     * Groups chat into clusters of at least [CHAT_CLUSTER_MIN] messages within
     * [CHAT_WINDOW] seconds; a single message is not a reason to cut.
     */
    private fun chatClusters(timeline: Timeline, prefs: SuggestPrefs): List<Interval> {
        val chats = timeline.chats
        if (chats.size < CHAT_CLUSTER_MIN) return emptyList()
        val out = ArrayList<Interval>()
        var start = chats.first()
        var last = start
        var count = 0

        fun flush() {
            if (count >= CHAT_CLUSTER_MIN) {
                out.add(
                    Interval(
                        start, last,
                        listOf(Reason("CHAT", start, "聊天密集 $count 条/60s", prefs.weightChat))
                    )
                )
            }
        }

        for (t in chats) {
            if (t - start > CHAT_WINDOW) {
                flush()
                start = t
                count = 0
            }
            last = t
            count++
        }
        // The final window has no following message to close it, so flush explicitly.
        flush()
        return out
    }

    /** Unions overlapping intervals and closes gaps below [gap]. */
    private fun glue(intervals: List<Interval>, gap: Double): List<Interval> {
        if (intervals.isEmpty()) return emptyList()
        val sorted = intervals.sortedBy { it.start }
        val out = ArrayList<Interval>(sorted.size)
        var current = sorted.first()
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.start - current.end <= gap) {
                current = Interval(
                    current.start,
                    maxOf(current.end, next.end),
                    current.reasons + next.reasons
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
     * Removes blocks too short to be worth cutting.
     *
     * A short block adjacent to a longer one is *absorbed* rather than dropped — the
     * events still matter, they just belong to the neighbouring cut. Only an isolated
     * short block is discarded outright.
     */
    private fun deJitter(blocks: List<Interval>, prefs: SuggestPrefs): List<Interval> {
        if (blocks.isEmpty()) return emptyList()
        val keep = BooleanArray(blocks.size) { blocks[it].end - blocks[it].start >= prefs.minBlock }
        val absorbed = ArrayList<Interval>()
        for (i in blocks.indices) {
            if (keep[i]) {
                absorbed.add(blocks[i])
                continue
            }
            val prev = blocks.getOrNull(i - 1)
            val next = blocks.getOrNull(i + 1)
            val nearPrev = prev != null && blocks[i].start - prev.end <= prefs.absorbGap
            val nearNext = next != null && next.start - blocks[i].end <= prefs.absorbGap
            when {
                // Merge into whichever neighbour it actually touches.
                nearPrev -> {
                    val target = absorbed.last()
                    absorbed[absorbed.size - 1] = Interval(
                        target.start, maxOf(target.end, blocks[i].end), target.reasons + blocks[i].reasons
                    )
                }
                nearNext -> absorbed.add(blocks[i])
                else -> Unit // isolated jitter: drop it
            }
        }
        // Fold the "absorbed for a later neighbour" case back together.
        return glue(absorbed, 0.0)
    }

    /**
     * Splits an over-long block at its quietest point rather than at a fixed offset,
     * so the cut does not land in the middle of a burst.
     */
    private fun splitIfTooLong(block: Interval, prefs: SuggestPrefs, timeline: Timeline): List<Interval> {
        val length = block.end - block.start
        if (length <= prefs.maxBlock) return listOf(block)
        val pieces = Math.ceil(length / prefs.maxBlock).toInt()
        val target = length / pieces
        val toyStarts = timeline.toyIntervals.map { it.startSeconds }.sorted()
        val out = ArrayList<Interval>(pieces)
        var cursor = block.start
        while (cursor < block.end - 1.0) {
            var next = (cursor + target).coerceAtMost(block.end)
            if (next < block.end) {
                // Snap the boundary to just before the nearest toy start so the split
                // falls in a lull.
                val candidate = toyStarts
                    .filter { it > cursor + target * 0.5 && it < cursor + target * 1.5 }
                    .minByOrNull { kotlin.math.abs(it - (cursor + target)) }
                if (candidate != null) next = candidate
                next = next.coerceIn(cursor + 1.0, block.end)
            }
            out.add(Interval(cursor, next, block.reasons))
            cursor = next
        }
        return out
    }

    /** Scores a block by the signals inside it, normalised by its length. */
    private fun scored(block: Interval, timeline: Timeline, prefs: SuggestPrefs): SuggestedBlock {
        val inside = timeline.toyIntervals.filter { it.startSeconds < block.end && it.endSeconds > block.start }
        val toySeconds = inside.sumOf {
            (minOf(it.endSeconds, block.end) - maxOf(it.startSeconds, block.start)).coerceAtLeast(0.0)
        }
        val peak = inside.maxOfOrNull { it.power.ordinalLevel } ?: 0
        val gifts = timeline.gifts.filter { it.seconds in block.start..block.end }
        val tipTotal = gifts.sumOf { it.amount }
        val specials = timeline.toySpecials.filter { it.seconds in block.start..block.end }

        var weighted = 0.0
        weighted += toySeconds * prefs.weightToy
        // Under the pure toy rule nothing but runtime may move the ranking; the
        // counters below are still reported for context, just not scored.
        if (!prefs.toyOnly) {
            // Micro-gifts are the norm, so amount is log-scaled or one whale dominates.
            // Toy-triggering tips are excluded unless asked for: their effect is
            // already counted as runtime above, and counting the amount too would
            // score one action twice.
            val scoredTips = gifts
                .filter { prefs.scoreToyTips || it.source != "interactiveToy" }
                .sumOf { it.amount }
            weighted += Math.log10(1.0 + scoredTips) * 4.0 * prefs.weightTip
            weighted += specials.size * prefs.weightSpecial * 3.0
            weighted += gifts.count { it.source?.startsWith("show:") == true } * prefs.weightShow * 6.0
            weighted += gifts.count { it.source == "king" } * prefs.weightKing * 6.0
            weighted += timeline.goals.count { it.seconds in block.start..block.end } * prefs.weightGoal
            weighted += timeline.chats.count { it in block.start..block.end } * prefs.weightChat
        }

        val length = (block.end - block.start).coerceAtLeast(1.0)
        // Per-minute density, so a long quiet block cannot outrank a short intense one.
        val score = weighted / length * 60.0

        val reasons = block.reasons
            .groupBy { it.kind }
            .map { (kind, list) -> list.maxByOrNull { it.weight } ?: list.first() }
            .sortedByDescending { it.weight }
            .take(6)

        return SuggestedBlock(
            start = block.start,
            end = block.end,
            score = score,
            toySeconds = toySeconds,
            tipTotal = tipTotal,
            specialCount = specials.size,
            peakPower = peak,
            reasons = reasons
        )
    }

    /** Convenience overload used by the HTTP layer. */
    fun suggest(events: List<ParsedEvent>, timeline: Timeline, duration: Double, prefs: SuggestPrefs) =
        suggest(timeline, duration, prefs)

    private const val CHAT_WINDOW = 60.0
    private const val CHAT_CLUSTER_MIN = 5

    /** Kinds that carry no cutting signal on their own. */
    val IGNORED_KINDS = setOf(EventKind.STREAM, EventKind.BAN, EventKind.TOY_SETTINGS, EventKind.OTHER)
}
