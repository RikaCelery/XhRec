package github.rikacelery.v3.components

import github.rikacelery.v3.core.DataChannel
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.data.StreamEnd
import github.rikacelery.v3.events.CutPoint
import github.rikacelery.v3.events.CutPointDone
import github.rikacelery.v3.events.EndReason
import github.rikacelery.v3.hooks.EventHook
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * A cut point is what closes a room's file. It must work even when the session never got as
 * far as downloading a segment — the room is then unknown to the downloader, and dropping the
 * cut leaves the file open, the session stuck in Closing and the scheduler in Stopping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloaderCutPointTest {

    @Test
    fun `cut point for a room without downloads still closes the stream`() = runTest {
        val dataChannel = DataChannel()
        val eventBus = EventBus()
        val cutPoints = mutableListOf<CutPointDone>()
        eventBus.installHook(object : EventHook {
            override suspend fun intercept(event: Any): Any? {
                if (event is CutPointDone) cutPoints += event
                return event
            }
        })
        val downloader = DownloaderComponent(dataChannel, emptyList(), eventBus, backgroundScope)

        downloader.handle(
            DoCutPoint(CutPoint(7L, -1, "model", Instant.now(), EndReason.UserStop, "highest", generation = 42L))
        )

        val end = withTimeout(5.seconds) { dataChannel.receive() }
        assertIs<StreamEnd>(end, "the writer closes the file on StreamEnd, got $end")
        assertEquals(7L, end.roomId)
        assertEquals(EndReason.UserStop, end.reason)
        assertEquals(listOf(CutPointDone(7L, 42L, EndReason.UserStop)), cutPoints)
    }
}
