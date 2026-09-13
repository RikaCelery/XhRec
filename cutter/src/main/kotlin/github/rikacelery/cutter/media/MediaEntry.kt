package github.rikacelery.cutter.media

import kotlinx.serialization.Serializable

/**
 * One recording discovered under a media root.
 *
 * [id] is a stable hash of [relPath] rather than the path itself: it survives the
 * media root being mounted somewhere else inside the container, and keeps absolute
 * server paths out of URLs.
 */
@Serializable
data class MediaEntry(
    val id: String,
    val relPath: String,
    val fileName: String,
    val room: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    /** Recording start, UTC epoch seconds, parsed from the file name. */
    val startEpochSeconds: Long?,
    /** Duration parsed from the file name; null when the name carries none. */
    val nameDurationSeconds: Long?,
    /** Sibling `.event` file, relative to the same root. */
    val eventRelPath: String?,
    /** Cheap event counters, filled by the background enrichment pass. */
    val heat: Heat? = null
) {
    val hasEvents: Boolean get() = eventRelPath != null
}

/**
 * Per-recording event summary. Computed with a fast substring pass over the event
 * file so the library can be ranked without a full JSON parse of ~11k files.
 */
@Serializable
data class Heat(
    val toyCommands: Int = 0,
    /** Sum of `detail.time` over toy commands — the FIFO-implied busy seconds. */
    val toySeconds: Double = 0.0,
    val specialCommands: Int = 0,
    val tips: Int = 0,
    val tipTotal: Long = 0,
    val chats: Int = 0,
    val goalEvents: Int = 0,
    val showEvents: Int = 0,
    val maxPower: Int = 0
) {
    val score: Double
        get() = toySeconds * 1.8 + specialCommands * 2.5 + tipTotal.coerceAtMost(100_000) / 1000.0 +
            showEvents * 3.0 + goalEvents * 0.5 + chats * 0.002
}

/** Ordinal toy intensity. `detail.power` is the authoritative source. */
enum class Power(val ordinalLevel: Int, val label: String) {
    NONE(0, "none"),
    LOW(1, "low"),
    MEDIUM(2, "medium"),
    HIGH(3, "high"),
    ULTRA(4, "ultraHigh");

    companion object {
        fun from(raw: String?): Power = when (raw?.lowercase()) {
            "low" -> LOW
            "medium" -> MEDIUM
            "high" -> HIGH
            "ultrahigh", "ultra_high", "ultra" -> ULTRA
            else -> NONE
        }
    }
}
