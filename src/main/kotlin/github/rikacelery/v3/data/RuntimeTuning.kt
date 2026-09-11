package github.rikacelery.v3.data

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class RuntimeTuning(
    val roomPollInterval: Duration = 5.minutes,
    val roomRefreshDebounce: Duration = 1500.milliseconds,
    val webSocketReconnectInitial: Duration = 1.seconds,
    val webSocketReconnectMax: Duration = 30.seconds,
    val preconfigRetryInterval: Duration = 15.seconds,
    /** Watchdog for the "is the selected variant playlist fetchable?" probe of a preconfig attempt. */
    val preconfigProbeTimeout: Duration = 5.seconds,
    val playlistPollInterval: Duration = 3.seconds,
    val playlistFetchTimeout: Duration = 10.seconds,
    /**
     * How long a recording session may go without a single new media segment before it is
     * considered stalled. A playlist that only advertises `#EXT-X-MAP` (init) and no
     * `#EXT-X-MOUFLON` segments keeps the session in `Recording` while nothing is downloaded, so
     * without this watchdog the dashboard reports a recording that produces no bytes.
     */
    val sessionStallTimeout: Duration = 90.seconds,
    /**
     * How long segments may keep being skipped by the resume mark (`lastSegmentId`) before the
     * session says so once at WARN. Short skips are normal after a cut and must stay silent; a
     * long one means the stream is behind the resume mark and nothing is being recorded.
     */
    val thresholdSkipLogDelay: Duration = 30.seconds,
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
