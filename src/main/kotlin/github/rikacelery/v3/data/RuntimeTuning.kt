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
    val playlistPollInterval: Duration = 3.seconds,
    val playlistFetchTimeout: Duration = 10.seconds,
    val httpRestartDelay: Duration = 500.milliseconds,
    val downloaderRaceDelay: Duration = 8.seconds,
    val downloaderAttemptTimeout: Duration = 25.seconds,
    val downloaderDeadline: Duration = 120.seconds,
    val downloaderStallTimeout: Duration = 5.seconds,
    val downloaderRetryBackoff: Duration = 500.milliseconds
)
