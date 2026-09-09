package github.rikacelery.v3.data

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RuntimeTuningTest {

    @Test
    fun `defaults preserve production timing values`() {
        val tuning = RuntimeTuning()

        assertEquals(5.minutes, tuning.roomPollInterval)
        assertEquals(1500.milliseconds, tuning.roomRefreshDebounce)
        assertEquals(1.seconds, tuning.webSocketReconnectInitial)
        assertEquals(30.seconds, tuning.webSocketReconnectMax)
        assertEquals(15.seconds, tuning.preconfigRetryInterval)
        assertEquals(3.seconds, tuning.playlistPollInterval)
        assertEquals(10.seconds, tuning.playlistFetchTimeout)
        assertEquals(500.milliseconds, tuning.httpRestartDelay)
        assertEquals(8.seconds, tuning.downloaderRaceDelay)
        assertEquals(25.seconds, tuning.downloaderAttemptTimeout)
        assertEquals(120.seconds, tuning.downloaderDeadline)
        assertEquals(5.seconds, tuning.downloaderStallTimeout)
        assertEquals(500.milliseconds, tuning.downloaderRetryBackoff)
    }
}
