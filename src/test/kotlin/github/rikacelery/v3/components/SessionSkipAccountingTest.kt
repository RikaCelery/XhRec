package github.rikacelery.v3.components

import github.rikacelery.v3.core.DataChannel
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.events.Segment
import github.rikacelery.v3.m3u8.M3u8Parser
import github.rikacelery.v3.m3u8.ParsedPlaylist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The playlist normally overlaps what was already downloaded, so a few segments are skipped on
 * every poll and that is silent by design. When *every* advertised segment sits at or below the
 * resume mark, however, the session is not recording anything while still reporting `Recording` —
 * that has to be visible, but exactly once, with a matching line when it finally catches up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionSkipAccountingTest {

    @Test
    fun `segments below the resume mark are counted as skipped, not enqueued`() =
        runTest(UnconfinedTestDispatcher()) {
            val entry = newEntry(backgroundScope)
            try {
                entry.lastSegmentId = 200L

                val unseen = entry.computeUnseen(playlist(segUrl("100"), segUrl("150")))

                assertEquals(
                    1, unseen.size,
                    "only the init is enqueued; every advertised media segment is at or below the mark"
                )
                assertEquals(-1, unseen.single().index, "the enqueued entry is the init segment")
                assertFalse(entry.lastPollEnqueuedMedia, "the init alone must not count as media progress")
                assertEquals(
                    2, entry.lastPollSegmentCount,
                    "the playlist did advertise segments — this is what separates a filtered poll from an empty one"
                )
                assertEquals(2L, entry.skippedInStreak)
                assertEquals(100L, entry.skippedMinId)
                assertEquals(150L, entry.skippedMaxId)
                assertNotNull(entry.skipStreakSince)
            } finally {
                entry.scope.cancel()
            }
        }

    @Test
    fun `a segment past the resume mark resets the streak`() = runTest(UnconfinedTestDispatcher()) {
        val entry = newEntry(backgroundScope)
        try {
            entry.lastSegmentId = 100L
            entry.computeUnseen(playlist(segUrl("50")))
            assertFalse(entry.lastPollEnqueuedMedia)

            val unseen = entry.computeUnseen(playlist(segUrl("150")))

            assertEquals(1, unseen.size, "the segment past the mark is downloaded")
            assertEquals(150, unseen.single().index, "and it is the media segment, not the init")
            assertTrue(entry.lastPollEnqueuedMedia)
            assertEquals(150L, entry.lastSegmentId, "the mark advances to the newest segment")
        } finally {
            entry.scope.cancel()
        }
    }

    @Test
    fun `a long skip streak is reported once and the catch-up is reported too`() =
        runTest(UnconfinedTestDispatcher()) {
            val entry = newEntry(backgroundScope)
            try {
                entry.lastSegmentId = 900L
                entry.computeUnseen(playlist(segUrl("800"), segUrl("850")))
                entry.noteSkipOutcome(progressed = false)

                // a fresh streak has not lasted long enough to be worth a line
                assertFalse(entry.skipStreakReported, "a short streak stays silent")

                // age the streak past the reporting delay, the way a real stall would
                entry.skipStreakSince = Instant.now().minusSeconds(120)
                entry.noteSkipOutcome(progressed = false)
                assertTrue(entry.skipStreakReported, "a persistent streak is reported once")
                assertEquals(2L, entry.skippedInStreak)
                assertEquals(800L, entry.skippedMinId)

                // repeated polls must not report again
                entry.noteSkipOutcome(progressed = false)
                assertTrue(entry.skipStreakReported)

                // catching up ends the streak and clears the accounting
                entry.noteSkipOutcome(progressed = true)
                assertNull(entry.skipStreakSince)
                assertEquals(0L, entry.skippedInStreak)
                assertFalse(entry.skipStreakReported)
                assertNull(entry.skippedMaxId)
            } finally {
                entry.scope.cancel()
            }
        }

    /** `<room>_<segmentId>_<16 alnum>_<10 digits>.mp4` is the shape the id parser expects. */
    private fun segUrl(id: String) = "https://media.example/$id" + "_${id}_ABCDEFGHIJKLMNOP_1700000000.mp4"

    private fun playlist(vararg urls: String) = ParsedPlaylist(
        "https://media.example/room_init_1.mp4",
        urls.map { Segment(it, M3u8Parser.segmentIDFromUrl(it) ?: 0) }
    )

    private fun newEntry(scope: CoroutineScope): SessionEntry {
        val eventBus = EventBus()
        val requestBus = RequestBus(eventBus, scope)
        val dataChannel = DataChannel()
        val downloader = DownloaderComponent(dataChannel, eventBus = eventBus, parentScope = scope)
        val component = SessionComponent(dataChannel, downloader, M3u8Parser, requestBus, eventBus, scope)
        return SessionEntry(7, "model", component)
    }
}
