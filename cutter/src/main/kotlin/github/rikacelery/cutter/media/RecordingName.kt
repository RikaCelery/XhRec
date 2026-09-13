package github.rikacelery.cutter.media

import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Wall-clock start and duration recovered from a recording's file name.
 *
 * XhRec writes `<room>-<stamp>-<dur>.mp4`, where `<dur>` is `1h2m3s`, `2m2s`, `3s`
 * or `3h` (FixStampProcessor adds a `.fixed` infix before the extension). The stamp
 * itself appears in two shapes in the production corpus, and both must parse:
 *
 *   - `mss_mar-2026-08-27-170503-00h00m30s.fixed.mp4`   (dashes, compact time)
 *   - `Yaya--728-2026_03_18_19_58_50-00h06m03s.fixed.mp4` (underscores, spaced time)
 *
 * Room names may themselves contain digits and hyphens (`xiao-Lin`, `_Galax-`,
 * `office-diodio`), so the stamp is located by scanning and the *last* match wins —
 * a room called `cam-2026-01-01` must not shadow the real stamp.
 */
data class RecordingStamp(
    val startLocal: LocalDateTime?,
    val durationSeconds: Long?
) {
    fun startEpochSeconds(zone: ZoneId): Long? =
        startLocal?.atZone(zone)?.toEpochSecond()

    companion object {
        val UNKNOWN = RecordingStamp(null, null)
    }
}

object RecordingName {

    /** Date plus compact time: `2026-08-27-170503`. */
    private val STAMP_COMPACT = Regex("""(\d{4})[-_](\d{2})[-_](\d{2})[-_](\d{6})""")

    /** Date plus spaced time: `2026_03_18_19_58_50`. */
    private val STAMP_SPACED = Regex("""(\d{4})[-_](\d{2})[-_](\d{2})[-_](\d{2})[-_](\d{2})[-_](\d{2})""")

    /** Original XhRec format: `room@2026年06月14日17时12分51秒`. */
    private val STAMP_LEGACY = Regex("""(\d{4})年(\d{2})月(\d{2})日(\d{2})时(\d{2})分(\d{2})秒""")

    /**
     * Duration block, scanned only in the text *after* the stamp so a duration-like
     * run inside a room name can never be picked up. At least one unit must be
     * present, otherwise the group is not a duration at all.
     */
    private val DURATION = Regex("""(?<![\d])(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)(?![\d])""")
    private val DURATION_HOURS_ONLY = Regex("""(?<![\d])(\d+)h(?![\d])""")

    /** A located stamp: where it starts and what it means. */
    private data class LocatedStamp(val start: Int, val end: Int, val value: LocalDateTime)

    private fun locateStamp(fileName: String): LocatedStamp? {
        // Legacy names carry `@<start>-<end>`, so the start is the *first* stamp.
        // Modern names carry `room-<start>-<dur>` and a room may contain a
        // date-like run, so there the *last* stamp is the real one.
        STAMP_LEGACY.find(fileName)?.let { m ->
            val g = m.groupValues
            build(g[1], g[2], g[3], g[4], g[5], g[6])?.let {
                return LocatedStamp(m.range.first, m.range.last + 1, it)
            }
        }

        var best: LocatedStamp? = null
        for (m in STAMP_COMPACT.findAll(fileName)) {
            val g = m.groupValues
            val value = build(g[1], g[2], g[3], g[4].substring(0, 2), g[4].substring(2, 4), g[4].substring(4, 6))
            if (value != null && (best == null || m.range.first > best.start)) {
                best = LocatedStamp(m.range.first, m.range.last + 1, value)
            }
        }
        for (m in STAMP_SPACED.findAll(fileName)) {
            val g = m.groupValues
            val value = build(g[1], g[2], g[3], g[4], g[5], g[6])
            if (value != null && (best == null || m.range.first > best.start)) {
                best = LocatedStamp(m.range.first, m.range.last + 1, value)
            }
        }
        return best
    }

    private fun build(y: String, mo: String, d: String, h: String, mi: String, s: String): LocalDateTime? =
        runCatching {
            LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt())
        }.getOrNull()

    fun parse(fileName: String): RecordingStamp {
        val stamp = locateStamp(fileName) ?: return RecordingStamp.UNKNOWN
        return RecordingStamp(stamp.value, durationAfter(fileName, stamp.end))
    }

    private fun durationAfter(fileName: String, from: Int): Long? {
        val tail = fileName.substring(from.coerceAtMost(fileName.length))
        DURATION.find(tail)?.let { m ->
            val (h, min, s) = m.destructured
            if (h.isNotEmpty() || min.isNotEmpty() || s.isNotEmpty()) {
                val total = (h.toLongOrNull() ?: 0L) * 3600 +
                    (min.toLongOrNull() ?: 0L) * 60 +
                    (s.toLongOrNull() ?: 0L)
                if (total > 0) return total
            }
        }
        // `1h` with no minutes or seconds still carries a duration.
        DURATION_HOURS_ONLY.find(tail)?.let { m ->
            val total = (m.groupValues[1].toLongOrNull() ?: 0L) * 3600
            if (total > 0) return total
        }
        return null
    }

    /**
     * Room label: the text before the stamp, minus the single separator that joined
     * it to the stamp.
     *
     * Exactly one separator character is removed. Rooms legitimately end in `_` or
     * `-` (`_Galax-`, `NATAASHA___`), so stripping every trailing separator would
     * fold distinct rooms together and stop matching the directory the file lives in.
     */
    fun room(fileName: String): String {
        val stamp = locateStamp(fileName) ?: return fileName.removeSuffix(".mp4")
        var raw = fileName.substring(0, stamp.start)
        if (raw.isNotEmpty() && (raw.last() == '-' || raw.last() == '_')) raw = raw.dropLast(1)
        // Legacy names embed the model id as `[146535429]_Name_@`; drop both wrappers.
        raw = raw.trimEnd('@')
        val bracket = raw.indexOf(']')
        if (raw.startsWith('[') && bracket in 0 until raw.length - 1) raw = raw.substring(bracket + 1)
        return raw.ifEmpty { "unknown" }
    }
}
