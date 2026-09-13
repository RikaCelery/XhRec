package github.rikacelery.cutter.events

import github.rikacelery.cutter.media.Power

/** Semantic class of a recording event, derived from the channel and message type. */
enum class EventKind {
    CHAT,
    TIP,
    /** A toy command being *executed* — see [ParsedEvent.toySeconds] for the FIFO note. */
    TOY_CMD,
    /** Toy menu/level configuration, not an action. */
    TOY_SETTINGS,
    GOAL,
    SHOW,
    KING,
    BAN,
    STREAM,
    OTHER
}

/** How [ParsedEvent.mediaSeconds] was obtained. Surfaced in the UI so the user knows. */
enum class TimeConfidence {
    /** The event carries its own occurrence timestamp. */
    EXACT,
    /** No own timestamp; linearly interpolated between two timestamped neighbours. */
    INTERPOLATED,
    /** Outside the timestamped range; extrapolated from the average line rate. */
    EXTRAPOLATED,
    /** The file has no usable timestamp at all, so no time could be assigned. */
    NONE
}

data class GoalInfo(
    val goal: Long?,
    val spent: Long?,
    val left: Long?,
    val description: String?,
    val isEnabled: Boolean?
)

data class ParsedEvent(
    /** 0-based line number; the ordering key used for interpolation. */
    val lineIndex: Int,
    val kind: EventKind,
    val channel: String,
    /** Occurrence timestamp carried by the event itself, in UTC epoch ms. */
    val ownEpochMs: Long?,
    /** Position on the media timeline, filled in by [EventTimeMapper]. */
    val mediaSeconds: Double? = null,
    val confidence: TimeConfidence = TimeConfidence.NONE,
    val user: String? = null,
    val text: String? = null,
    val amount: Long? = null,
    val tipSource: String? = null,
    val triggerType: String? = null,
    /** `specialActualValue`, e.g. `giveControl`, `earthquake`, `clear`. */
    val toyAction: String? = null,
    /** `detail.time`: how long this command runs the toy, in seconds. */
    val toySeconds: Double? = null,
    val toyPower: Power = Power.NONE,
    val goal: GoalInfo? = null,
    val showState: String? = null,
    val streamStatus: String? = null
) {
    /** A command that stops whatever is currently running. */
    val isToyStop: Boolean get() = kind == EventKind.TOY_CMD && toyAction in TOY_STOP_ACTIONS

    companion object {
        val TOY_STOP_ACTIONS = setOf("clear", "pause", "stop")
    }
}

/** A contiguous stretch during which the toy is running. */
data class ToyInterval(
    val startSeconds: Double,
    val endSeconds: Double,
    val power: Power,
    val action: String?,
    val user: String?,
    val amount: Long?
) {
    val duration get() = (endSeconds - startSeconds).coerceAtLeast(0.0)
}

/** A toy action worth showing as its own marker (no meaningful duration). */
data class SpecialMarker(
    val seconds: Double,
    val action: String,
    val user: String?,
    val amount: Long?
)

/** A gift event with a token amount. */
data class GiftMarker(
    val seconds: Double,
    val amount: Long,
    val source: String?,
    val user: String?,
    val triggerType: String?
)

data class GoalMarker(
    val seconds: Double,
    val goal: Long?,
    val spent: Long?,
    val description: String?
)

/** Everything the timeline lanes need, all in media seconds. */
data class Timeline(
    val toyIntervals: List<ToyInterval>,
    /**
     * The intensity curve, kept separate from [toyIntervals].
     *
     * [toyIntervals] are gap-merged into activity blocks, and a block reports the
     * *peak* power inside it. Colouring the level lane from those would paint an
     * entire multi-hour block at its loudest single moment, which is how a recording
     * with one `ultraHigh` command in six hours ends up looking uniformly maximal.
     * These segments are only fused where consecutive commands share a power, so the
     * curve stays truthful.
     */
    val levelSegments: List<ToyInterval>,
    val toySpecials: List<SpecialMarker>,
    val gifts: List<GiftMarker>,
    val goals: List<GoalMarker>,
    val chats: List<Double>,
    val events: List<ParsedEvent>,
    val stats: EventStats
)

data class EventStats(
    val totalLines: Int,
    val unparseableLines: Int,
    val withOwnTimestamp: Int,
    val toyCommandCount: Int,
    /** Raw sum of every command's `time`; not the same as wall-clock busy time. */
    val toySecondsSum: Double,
    /** Union of the toy intervals — the real busy time. */
    val toyBusySeconds: Double,
    val specialCommandCount: Int,
    val tipCount: Int,
    val tipTotal: Long,
    val chatCount: Int,
    val goalCount: Int,
    val showCount: Int,
    val maxPower: Power,
    val firstEventSeconds: Double?,
    val lastEventSeconds: Double?,
    val confidence: TimeConfidence
)
