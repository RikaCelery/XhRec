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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a session accounts for in the playlist it polls, in both directions.
 *
 * *Skipping* is the normal steady state: the playlist is a sliding window that overlaps the resume
 * mark, so a few entries are already written and are simply not queued again. That must never be
 * reported, and must never reach the metrics — it happens on every poll of every healthy room. What
 * is worth reporting is the mark running far *ahead* of the playlist, because then nothing can be
 * recorded at all until the stream catches up.
 *
 * *Missing* entries are the opposite failure: ids the stream published that the playlist never
 * showed us, because it advanced past them between two polls or carries a hole. Nothing else in the
 * pipeline can see those — a segment we never hear about never fails and never reaches the
 * downloader — so the session counts them itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionSegmentAccountingTest {

    @Test
    fun `entries below the resume mark are skipped, not enqueued`() =
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
                assertEquals(2, entry.lastPollSkipped, "the poll reports how many entries it did not queue")
            } finally {
                entry.scope.cancel()
            }
        }

    @Test
    fun `a segment past the resume mark advances the mark`() = runTest(UnconfinedTestDispatcher()) {
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
    fun `an overlap with the resume mark is normal and stays silent`() = runTest(UnconfinedTestDispatcher()) {
        val entry = newEntry(backgroundScope)
        try {
            // the everyday case: the window and the mark join up, so the mark never leads
            entry.computeUnseen(playlist(segUrl("1061"), segUrl("1062"), segUrl("1063")))
            entry.computeUnseen(playlist(segUrl("1063"), segUrl("1064"), segUrl("1065")))

            assertEquals(0L, entry.lastPollMarkAhead, "the mark and the window join up")
            assertFalse(entry.markAheadReported, "an ordinary overlap must never be reported")
            assertFalse(entry.lastPollMarkAheadJustReported, "and must never reach the metrics")
        } finally {
            entry.scope.cancel()
        }
    }

    @Test
    fun `a resume mark far ahead of the playlist is reported once per episode`() =
        runTest(UnconfinedTestDispatcher()) {
            val entry = newEntry(backgroundScope)
            try {
                entry.lastSegmentId = 1750L

                entry.computeUnseen(playlist(segUrl("1002"), segUrl("1003"), segUrl("1004")))

                assertEquals(746L, entry.lastPollMarkAhead, "the mark is 746 ids ahead of the newest advertised")
                assertTrue(entry.markAheadReported)
                assertTrue(entry.lastPollMarkAheadJustReported, "the first poll of the episode publishes it")

                // still stuck on the next poll, but the episode was already reported
                entry.computeUnseen(playlist(segUrl("1002"), segUrl("1003"), segUrl("1004")))
                assertEquals(746L, entry.lastPollMarkAhead)
                assertFalse(entry.lastPollMarkAheadJustReported, "a reported backlog is not repeated")
            } finally {
                entry.scope.cancel()
            }
        }

    @Test
    fun `a lead within the threshold is not a backlog`() = runTest(UnconfinedTestDispatcher()) {
        val entry = newEntry(backgroundScope)
        try {
            entry.lastSegmentId = 1070L
            entry.computeUnseen(playlist(segUrl("1050"), segUrl("1060")))
            assertFalse(entry.markAheadReported, "a lead of exactly the threshold is still normal")

            entry.lastSegmentId = 1071L
            entry.computeUnseen(playlist(segUrl("1050"), segUrl("1060")))
            assertTrue(entry.markAheadReported, "one id past the threshold is reported")
        } finally {
            entry.scope.cancel()
        }
    }

    @Test
    fun `the stream catching up clears the backlog`() = runTest(UnconfinedTestDispatcher()) {
        val entry = newEntry(backgroundScope)
        try {
            entry.lastSegmentId = 1750L
            entry.computeUnseen(playlist(segUrl("1002"), segUrl("1003")))
            assertTrue(entry.markAheadReported)

            entry.computeUnseen(playlist(segUrl("1748"), segUrl("1749"), segUrl("1751")))

            assertEquals(0L, entry.lastPollMarkAhead)
            assertFalse(entry.markAheadReported, "the episode is over")
            assertNull(entry.markAheadSince)
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

    @Test
    fun `a jump in segment ids counts the ids that were never advertised`() =
        runTest(UnconfinedTestDispatcher()) {
            val entry = newEntry(backgroundScope)
            try {
                // first refresh is at id 0 ...
                entry.computeUnseen(playlist(segUrl("0")))
                assertEquals(0, entry.lastPollGap, "the first poll only establishes the baseline")

                // ... the second is already at 4, so 1, 2 and 3 were lost
                entry.computeUnseen(playlist(segUrl("4")))
                assertEquals(3, entry.lastPollGap, "ids 1,2,3 were never advertised")
            } finally {
                entry.scope.cancel()
            }
        }

    @Test
    fun `a hole inside the advertised window is counted too`() = runTest(UnconfinedTestDispatcher()) {
        val entry = newEntry(backgroundScope)
        try {
            entry.computeUnseen(playlist(segUrl("10"), segUrl("11"), segUrl("12")))
            entry.computeUnseen(playlist(segUrl("11"), segUrl("12"), segUrl("13")))
            assertEquals(0, entry.lastPollGap, "a normal advance loses nothing")

            // 14 follows the mark directly, but 15..19 are absent and 20 is not 15
            entry.computeUnseen(playlist(segUrl("13"), segUrl("14"), segUrl("20"), segUrl("21")))
            assertEquals(5, entry.lastPollGap, "15..19 are missing even though 14 is present")
        } finally {
            entry.scope.cancel()
        }
    }

    @Test
    fun `segments published while the playlist stood still are counted as missing`() =
        runTest(UnconfinedTestDispatcher()) {
            val entry = newEntry(backgroundScope)
            try {
                entry.computeUnseen(playlist(segUrl("5"), segUrl("6"), segUrl("7")))
                entry.computeUnseen(playlist(segUrl("6"), segUrl("7")))
                assertEquals(0, entry.lastPollGap, "a stalled window loses nothing by itself")

                entry.computeUnseen(playlist(segUrl("20"), segUrl("21"), segUrl("22")))
                assertEquals(12, entry.lastPollGap, "8..19 were published while nothing was queued")
            } finally {
                entry.scope.cancel()
            }
        }

    @Test
    fun `restarting a session forgets the gap baseline`() = runTest(UnconfinedTestDispatcher()) {
        val entry = newEntry(backgroundScope)
        try {
            entry.previousNewSegmentId = 500L
            entry.lastPollGap = 7

            entry.fsm.drive(RecordingEvent.StartRecording)
            entry.playlistLoop.cancel()

            // the seam after a restart (a limit cut, a re-arm) is expected, not lost data
            assertNull(entry.previousNewSegmentId)
            assertEquals(0, entry.lastPollGap)
        } finally {
            entry.scope.cancel()
        }
    }

    @Test
    fun `a one-element id range renders as a single id, not as a range`() {
        // `id 1063..1063` in a log reads like a typo and hides that only one entry was skipped
        assertEquals("1063", formatIdRange(1063L, 1063L))
        assertEquals("1061..1063", formatIdRange(1061L, 1063L))
        assertEquals("?", formatIdRange(null, null))
    }

    private fun newEntry(scope: CoroutineScope): SessionEntry {
        val eventBus = EventBus()
        val requestBus = RequestBus(eventBus, scope)
        val dataChannel = DataChannel()
        val downloader = DownloaderComponent(dataChannel, eventBus = eventBus, parentScope = scope)
        val component = SessionComponent(dataChannel, downloader, M3u8Parser, requestBus, eventBus, scope)
        return SessionEntry(7, "model", component)
    }
}
