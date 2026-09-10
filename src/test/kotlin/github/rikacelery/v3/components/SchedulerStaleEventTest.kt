package github.rikacelery.v3.components

import github.rikacelery.v3.api.ApiClient
import github.rikacelery.v3.core.DataChannel
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.data.RoomSettings
import github.rikacelery.v3.data.RuntimeTuning
import github.rikacelery.v3.events.EndReason
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
 * The scheduler FSM is deliberately exhaustive: an undefined (state, event) pair is a bug and is
 * reported as an ILLEGAL TRANSITION with a transition dump. Two pairs were reachable in normal use,
 * and each one spammed that dump instead of being handled:
 *
 *  - `Preconfiguring` + `SessionExit` — a session publishes its exit from `RecordingState.Closing`,
 *    which is only reached asynchronously after `StopRecording`. Stopping a room (or removing it)
 *    and then re-activating it therefore lets that stale exit land once the room is preconfiguring
 *    again.
 *  - `Armed` + `PreconfigDone`/`PreconfigFailed` — a preconfig attempt is answered off the actor
 *    loop, so its signal can already be queued when `BackToArmed` cancels the loop.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulerStaleEventTest {

    @Test
    fun `a session exit that lands while preconfiguring is ignored`() = runTest(UnconfinedTestDispatcher()) {
        val entry = newEntry(backgroundScope)
        try {
            entry.fsm.drive(SchedulerEvent.BeginPreconfig)
            entry.preconfigLoop.cancel() // this test exercises the transition, not the probe itself
            assertEquals(SchedulerState.Preconfiguring, entry.fsm.currentState)

            assertNull(
                entry.fsm.driveCatch(
                    SchedulerEvent.SessionExit,
                    SchedulerDriveData(exitReason = EndReason.UserStop)
                ),
                "the exit of the session that was stopped before re-activating the room must be ignored"
            )
            assertEquals(
                SchedulerState.Preconfiguring,
                entry.fsm.currentState,
                "a stale exit must not disturb the preconfig attempt in flight"
            )
        } finally {
            entry.scope.cancel()
        }
    }

    @Test
    fun `a preconfig answer that arrives after the loop was cancelled is ignored while armed`() =
        runTest(UnconfinedTestDispatcher()) {
            val entry = newEntry(backgroundScope)
            try {
                entry.fsm.drive(SchedulerEvent.BeginPreconfig)
                entry.fsm.drive(SchedulerEvent.BackToArmed) // the room stopped being recordable
                assertEquals(SchedulerState.Armed, entry.fsm.currentState)

                assertNull(
                    entry.fsm.driveCatch(
                        SchedulerEvent.PreconfigDone,
                        SchedulerDriveData(playlistUrl = "http://127.0.0.1:1/live.m3u8")
                    ),
                    "a preconfig answer already in flight when the loop was cancelled must be ignored"
                )
                assertNull(
                    entry.fsm.driveCatch(
                        SchedulerEvent.PreconfigFailed,
                        SchedulerDriveData(failReason = "late")
                    ),
                    "a late preconfig failure must be ignored"
                )
                assertEquals(SchedulerState.Armed, entry.fsm.currentState)
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
