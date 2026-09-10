package github.rikacelery.v3.components

import github.rikacelery.v3.api.ApiClient
import github.rikacelery.v3.core.DataChannel
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.data.RoomSettings
import github.rikacelery.v3.data.RuntimeTuning
import github.rikacelery.v3.events.CommandAck
import github.rikacelery.v3.events.CommandEnvelope
import github.rikacelery.v3.events.EndReason
import github.rikacelery.v3.events.GetRoomConfig
import github.rikacelery.v3.events.RoomConfigResponse
import github.rikacelery.v3.events.RoomStatusChanged
import github.rikacelery.v3.hooks.EventHook
import github.rikacelery.v3.m3u8.M3u8Parser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours

/**
 * Guard for issue #130: a session that ends on its own limit is normally restarted, but only
 * while the room is still recordable. Without that check, switching public recording off while
 * a limited session is running would silently start a fresh public recording.
 */
class SchedulerFilterGuardTest {

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `a session that ended on its limit is not restarted once the room is no longer recordable`() =
        runTest(UnconfinedTestDispatcher()) {
            val settings = RoomSettings(recordPublic = false, pkey = "streamKey")
            val eventBus = EventBus()
            val requestBus = RequestBus(eventBus, backgroundScope)
            val dataChannel = DataChannel()
            val downloader = DownloaderComponent(dataChannel, eventBus = eventBus, parentScope = backgroundScope)
            val session = SessionComponent(dataChannel, downloader, M3u8Parser, requestBus, eventBus, backgroundScope)
            val scheduler = SchedulerComponent(
                requestBus,
                session,
                // a dead loopback host: the preconfig attempts fail immediately instead of touching the network
                ApiClient(listOf("127.0.0.1:1")),
                "streamKey",
                eventBus,
                backgroundScope,
                runtimeTuning = RuntimeTuning(preconfigRetryInterval = 1.hours)
            )
            eventBus.installHook(object : EventHook {
                override suspend fun intercept(event: Any): Any? {
                    if (event is CommandEnvelope && event.command is GetRoomConfig) {
                        eventBus.publish(CommandAck(event.id, RoomConfigResponse(settings)))
                    }
                    return event
                }
            })
            scheduler.start()
            advanceUntilIdle()

            try {
                val entry = scheduler.internalAdd(42, "model", settings, isArmed = true)!!
                scheduler.tell(SchedulerSignal(42, SchedulerEvent.RoomStatusChanged, SchedulerDriveData(roomStatus = "public")))
                advanceUntilIdle()

                scheduler.tell(SchedulerSignal(42, SchedulerEvent.BeginPreconfig, null))
                advanceUntilIdle()
                scheduler.tell(
                    SchedulerSignal(
                        42,
                        SchedulerEvent.PreconfigDone,
                        SchedulerDriveData(playlistUrl = "http://127.0.0.1:1/playlist.m3u8", quality = "720p")
                    )
                )
                advanceUntilIdle()
                assertEquals(
                    SchedulerState.Recording,
                    entry.fsm.currentState,
                    "precondition: the room is recording while its status makes it recordable"
                )

                // the session hits its time limit while public recording is switched off
                scheduler.tell(
                    SchedulerSignal(42, SchedulerEvent.SessionExit, SchedulerDriveData(exitReason = EndReason.TimeLimit))
                )
                advanceUntilIdle()

                assertEquals(
                    SchedulerState.Armed,
                    entry.fsm.currentState,
                    "a room whose public recording is off must not be restarted"
                )
            } finally {
                scheduler.stop()
            }
        }
}
