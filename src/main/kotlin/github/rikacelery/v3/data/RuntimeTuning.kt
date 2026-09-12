package github.rikacelery.v3.data

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class RuntimeTuning(
    val roomPollInterval: Duration = 5.minutes,
    val roomRefreshDebounce: Duration = 1500.milliseconds,
    /**
     * How long a room's status read absorbs further failure-driven refreshes: a session playlist
     * that turned 403/404 and, moments later, a preconfig probe that did the same collapse into one
     * immediate read plus at most one catch-up read at the end of this window, instead of two
     * immediate platform requests. Activation ignores the window: that refresh is a command, not a
     * hint.
     */
    val roomStatusRefreshWindow: Duration = 2.seconds,
    val webSocketReconnectInitial: Duration = 1.seconds,
    val webSocketReconnectMax: Duration = 30.seconds,
    val preconfigRetryInterval: Duration = 15.seconds,
    /** Watchdog for the "is the selected variant playlist fetchable?" probe of a preconfig attempt. */
    val preconfigProbeTimeout: Duration = 5.seconds,
    val playlistPollInterval: Duration = 3.seconds,
    val playlistFetchTimeout: Duration = 10.seconds,
    /**
     * Per-attempt read/connect timeout for a playlist request, applied as an HTTP-engine timeout.
     *
     * Deliberately smaller than [playlistFetchTimeout]: a pooled connection that stopped answering
     * must be aborted by the engine (which drops it and lets the retry dial a fresh one) instead of
     * consuming the whole fetch budget as one coroutine cancellation that no retry can observe.
     * Size it against the real warm latency (a reused connection answers in well under a second) —
     * too small turns a merely slow CDN into a host rotation.
     */
    val playlistAttemptTimeout: Duration = 4.seconds,
    /**
     * How long a recording session may go without a single new media segment before it is
     * considered stalled. A playlist that only advertises `#EXT-X-MAP` (init) and no
     * `#EXT-X-MOUFLON` segments keeps the session in `Recording` while nothing is downloaded, so
     * without this watchdog the dashboard reports a recording that produces no bytes.
     */
    val sessionStallTimeout: Duration = 90.seconds,
    /**
     * Upper bound on a cut point. `CutPointDone` is delivered over the event bus, which may drop
     * it, and the downloader may take up to [downloaderDeadline] to settle in-flight segments;
     * past this the session ends on its own so a room can never be stranded in Stopping.
     */
    val sessionCutTimeout: Duration = 3.minutes,
    /**
     * How far the resume mark may sit *ahead* of the newest advertised segment id before the
     * session reports a backlog.
     *
     * A healthy playlist overlaps the mark by a segment or two — the mark and the window "join up"
     * — so a small lead is normal and must stay silent. A mark far ahead means every advertised
     * segment is already recorded and nothing can be downloaded until the stream catches up, which
     * is exactly what a resume mark left over from an earlier broadcast looks like.
     */
    val resumeMarkAheadThreshold: Int = 10,
    /**
     * How often the debug stream writes a heartbeat when nothing else is happening. The write is
     * also how a dropped client is detected: an idle connection is never noticed otherwise, and the
     * bus taps would stay armed — and keep costing — for the life of the process.
     */
    val debugStreamHeartbeat: Duration = 10.seconds,
    val httpRestartDelay: Duration = 500.milliseconds,
    val downloaderRaceDelay: Duration = 8.seconds,
    val downloaderAttemptTimeout: Duration = 25.seconds,
    val downloaderDeadline: Duration = 120.seconds,
    val downloaderStallTimeout: Duration = 5.seconds,
    val downloaderRetryBackoff: Duration = 500.milliseconds
)
