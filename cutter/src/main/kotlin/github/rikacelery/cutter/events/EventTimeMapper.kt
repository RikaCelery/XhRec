package github.rikacelery.cutter.events

/**
 * Default shift from event time to media time, in seconds.
 *
 * A recording's file-name stamp is written when XhRec opens the output file, which
 * happens a few seconds after the platform's stream clock starts — so the stamp runs
 * ahead of the media timeline and every event lands late by that gap. Measured by
 * matching the panel overlay's first frame against the command's own `createdAt`:
 * **+7.0 s on four independent anchors**, spanning 19 min to 8.6 h inside one recording
 * and repeating on a different recording from a different day. It did not drift, so a
 * single default is right and this is not a per-hour correction.
 *
 * It stays a *display* offset: it only shifts the event lanes. Cut points, segments and
 * exports are expressed in video time, so this value can never move an edit.
 *
 * The UI exposes the same number (`DEFAULT_EVENT_OFFSET` in `cutter.html`) and lets the
 * user override it per recording; [github.rikacelery.cutter.web.Server] clamps whatever
 * arrives over the wire.
 */
const val DEFAULT_EVENT_NUDGE_SECONDS: Double = 7.0

/**
 * Places events on the media timeline.
 *
 * Only some events carry their own timestamp, so the rest are interpolated by
 * *line index* between their timestamped neighbours — the event file is strictly
 * append-ordered, so line order is a reliable monotonic proxy for time. Measured on
 * 33 real recordings: no event landed before its recording started, and the last
 * event fell within 1.0 s of the recording's own end.
 *
 * The conversion is
 *
 *     mediaSeconds = eventEpochSeconds - recordingStartEpochSeconds + nudge
 *
 * where `recordingStartEpochSeconds` comes from the file name (local wall clock,
 * interpreted in the configured zone) and event stamps are UTC.
 */
class EventTimeMapper(
    private val recordingStartEpochSeconds: Long?,
    private val nudgeSeconds: Double = DEFAULT_EVENT_NUDGE_SECONDS
) {

    /** Set after [map]; how many timestamps the monotonicity guard rejected. */
    var droppedStaleAnchors: Int = 0
        private set

    fun map(events: List<ParsedEvent>): List<ParsedEvent> {
        if (events.isEmpty()) return events

        val rawAnchors = events.filter { it.ownEpochMs != null }
        val anchors = consistentAnchors(rawAnchors)
        droppedStaleAnchors = rawAnchors.size - anchors.size
        val base = recordingStartEpochSeconds
            // With no parseable start we can still give relative positions by
            // treating the first timestamped event as t=0.
            ?: anchors.firstOrNull()?.ownEpochMs?.let { it / 1000 }

        if (anchors.isEmpty() || base == null) {
            return events.map { it.copy(mediaSeconds = null, confidence = TimeConfidence.NONE) }
        }

        // Average seconds per line across the timestamped span; used to extend
        // beyond the first and last anchor rather than piling events onto them.
        val first = anchors.first()
        val last = anchors.last()
        val anchorLines = anchors.mapTo(HashSet(anchors.size)) { it.lineIndex }
        val spanLines = (last.lineIndex - first.lineIndex).toDouble()
        val spanSeconds = (last.ownEpochMs!! - first.ownEpochMs!!).toDouble() / 1000.0
        val perLine = if (spanLines > 0 && spanSeconds > 0) spanSeconds / spanLines else 0.0

        return events.map { event ->
            val own = event.ownEpochMs
            // A timestamp the guard rejected is not this event's time either, so it
            // is interpolated like any untimed event rather than trusted as EXACT.
            if (own != null && event.lineIndex in anchorLines) {
                event.copy(
                    mediaSeconds = own / 1000.0 - base + nudgeSeconds,
                    confidence = TimeConfidence.EXACT
                )
            } else {
                val estimate = estimateEpoch(event.lineIndex, anchors, first, last, perLine)
                val raw = estimate.second / 1000.0 - base + nudgeSeconds
                // An estimate is a guess, and a guess before the start of the media
                // is meaningless: it would place markers off the timeline and let a
                // suggested cut fall outside the file. Pin it to 0 instead. EXACT
                // values are left alone so genuinely early events stay visible.
                event.copy(
                    mediaSeconds = raw.coerceAtLeast(0.0),
                    confidence = estimate.first
                )
            }
        }
    }

    /**
     * Keeps only timestamps that respect the file's append ordering.
     *
     * An event file is strictly append-ordered, so occurrence times must be
     * essentially non-decreasing. A timestamp that lands far *before* the running
     * maximum is a stale historical value rather than this event's time, and it is
     * dropped so it cannot anchor the interpolation. The tolerance absorbs ordinary
     * clock jitter and slight out-of-order delivery between the platform's servers.
     */
    private fun consistentAnchors(anchors: List<ParsedEvent>): List<ParsedEvent> {
        if (anchors.size < 2) return anchors
        val out = ArrayList<ParsedEvent>(anchors.size)
        var runMax = Long.MIN_VALUE
        for (anchor in anchors) {
            val ts = anchor.ownEpochMs ?: continue
            if (runMax != Long.MIN_VALUE && ts < runMax - STALE_TOLERANCE_MS) continue
            out.add(anchor)
            if (ts > runMax) runMax = ts
        }
        return out
    }

    private companion object {
        /**
         * How far behind the running maximum a timestamp may sit before it is
         * treated as stale. Real out-of-order delivery is seconds; the stale values
         * observed in the corpus are days.
         */
        const val STALE_TOLERANCE_MS = 60_000L

        /** Longest forward extrapolation past the last anchor, in millis. */
        const val MAX_TAIL_DRIFT_MS = 120_000.0
    }

    /**
     * Returns the estimated epoch millis plus how it was derived.
     *
     * Outside the anchor range the estimate is extrapolated at the anchor span's
     * average seconds-per-line, which is the best available signal when the file
     * opens with a burst of configuration events and only later gets its first chat.
     * Forward drift is capped so an unusually dense anchor span cannot fling trailing
     * events far past the end.
     */
    private fun estimateEpoch(
        lineIndex: Int,
        anchors: List<ParsedEvent>,
        first: ParsedEvent,
        last: ParsedEvent,
        perLine: Double
    ): Pair<TimeConfidence, Double> {
        if (lineIndex <= first.lineIndex) {
            val drift = (first.lineIndex - lineIndex) * perLine * 1000.0
            return TimeConfidence.EXTRAPOLATED to (first.ownEpochMs!! - drift)
        }
        if (lineIndex >= last.lineIndex) {
            val drift = ((lineIndex - last.lineIndex) * perLine * 1000.0)
                .coerceAtMost(MAX_TAIL_DRIFT_MS)
            return TimeConfidence.EXTRAPOLATED to (last.ownEpochMs!! + drift)
        }
        // Bracketing pair: the last anchor at or before this line, and the next one.
        var before: ParsedEvent? = null
        var after: ParsedEvent? = null
        for (anchor in anchors) {
            if (anchor.lineIndex <= lineIndex) {
                before = anchor
            } else {
                after = anchor
                break
            }
        }
        val lo = before
        val hi = after
        if (lo == null || hi == null) {
            return TimeConfidence.EXTRAPOLATED to last.ownEpochMs!!.toDouble()
        }
        val spanLines = (hi.lineIndex - lo.lineIndex).toDouble()
        if (spanLines <= 0) return TimeConfidence.INTERPOLATED to lo.ownEpochMs!!.toDouble()
        val ratio = (lineIndex - lo.lineIndex) / spanLines
        val loMs = lo.ownEpochMs!!.toDouble()
        val hiMs = hi.ownEpochMs!!.toDouble()
        return TimeConfidence.INTERPOLATED to (loMs + (hiMs - loMs) * ratio)
    }
}
