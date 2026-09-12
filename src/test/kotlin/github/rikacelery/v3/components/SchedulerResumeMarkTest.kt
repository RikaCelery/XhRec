package github.rikacelery.v3.components

import github.rikacelery.v3.api.ApiClient
import github.rikacelery.v3.core.DataChannel
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.data.RoomSettings
import github.rikacelery.v3.data.RuntimeTuning
import github.rikacelery.v3.m3u8.M3u8Parser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours

/**
 * The resume mark (`lastIndex`) exists so a cut inside one continuous stream does not re-download
 * the segments that were just written. The platform's segment ids restart with each broadcast, so
 * a mark left over from an earlier broadcast makes the next session skip *every* segment: the
 * playlist keeps listing them, `computeUnseen` drops them all, and the room reports `Recording`
 * while downloading nothing at all — until the fresh counter climbs past the stale mark, which can
 * take as long as the previous broadcast lasted.
 *
 * These tests pin the invariant: the mark only survives a restart *inside* one stream, and is
 * dropped by anything that re-resolves the stream.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulerResumeMarkTest {

    @Test
    fun `re-preconfiguring clears a resume mark left by a previous broadcast`() =
        runTest(UnconfinedTestDispatcher()) {
            val entry = newEntry(backgroundScope)
            try {
                entry.lastIndex = 1750L // the previous broadcast of this room got this far
                entry.fsm.drive(SchedulerEvent.BeginPreconfig)
                entry.preconfigLoop.cancel()

                assertNull(
                    entry.lastIndex,
                    "a mark from an earlier broadcast must not be applied to a re-resolved stream"
                )
            } finally {
                entry.scope.cancel()
            }
        }

    @Test
    fun `returning to armed clears a resume mark`() = runTest(UnconfinedTestDispatcher()) {
        val entry = newEntry(backgroundScope)
        try {
            entry.fsm.drive(SchedulerEvent.BeginPreconfig)
            entry.preconfigLoop.cancel()
            entry.lastIndex = 1750L

            entry.fsm.drive(SchedulerEvent.BackToArmed)

            assertNull(entry.lastIndex, "leaving for Armed invalidates the resume mark")
        } finally {
            entry.scope.cancel()
        }
    }

    @Test
    fun `a restart inside the same stream keeps the resume mark`() = runTest(UnconfinedTestDispatcher()) {
        val entry = newEntry(backgroundScope)
        try {
            entry.fsm.drive(SchedulerEvent.BeginPreconfig)
            entry.preconfigLoop.cancel()
            entry.fsm.drive(
                SchedulerEvent.PreconfigDone,
                SchedulerDriveData(playlistUrl = "http://127.0.0.1:1/room/720p.m3u8", quality = "720p", pkey = "room-key")
            )
            assertEquals(SchedulerState.Recording, entry.fsm.currentState)

            entry.lastIndex = 1750L
            entry.fsm.drive(SchedulerEvent.RestartRecording)

            assertEquals(
                1750L, entry.lastIndex,
                "a time/size limit cut continues the same stream, so the mark is still valid"
            )
        } finally {
            entry.scope.cancel()
        }
    }

    private fun newEntry(scope: CoroutineScope): SchedulerEntry {
        val eventBus = EventBus()
        val requestBus = RequestBus(eventBus, scope)
        val dataChannel = DataChannel()
        val downloader = DownloaderComponent(dataChannel, eventBus = eventBus, parentScope = scope)
        val session = SessionComponent(dataChannel, downloader, M3u8Parser, requestBus, eventBus, scope)
        val scheduler = SchedulerComponent(
            requestBus,
            session,
            // a dead loopback host: no component in this test reaches the network
            ApiClient(listOf("127.0.0.1:1")),
            "streamKey",
            eventBus,
            scope,
            runtimeTuning = RuntimeTuning(preconfigRetryInterval = 1.hours)
        )
        return SchedulerEntry(7, "model", scheduler).apply { settings = RoomSettings(pkey = "room-key") }
    }
}
