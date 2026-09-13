package github.rikacelery.cutter.project

import kotlinx.serialization.Serializable

/**
 * One cut segment.
 *
 * [end] is nullable because that is exactly how LosslessCut encodes a *marker*: a
 * segment with no `end` has zero length and is **not exported**. "Cut to the end of
 * the file" is stored as an explicit numeric end, never as a missing one. Getting
 * this backwards would silently turn every marker into a cut to EOF.
 */
@Serializable
data class CutSegment(
    val start: Double,
    val end: Double? = null,
    val name: String = "",
    val tags: Map<String, String> = emptyMap(),
    val selected: Boolean = true
) {
    val isMarker: Boolean get() = end == null
    val duration: Double get() = if (end == null) 0.0 else (end - start).coerceAtLeast(0.0)
}

/** A recording's edit state. Mirrors LosslessCut's `.llc` payload. */
@Serializable
data class CutProject(
    val version: Int = 2,
    val mediaFileName: String = "",
    val segments: List<CutSegment> = emptyList()
) {
    /** Segments that will actually be exported: selected, and not markers. */
    val exportable: List<CutSegment>
        get() = segments.filter { it.selected && !it.isMarker && it.duration > 0.0 }
}
