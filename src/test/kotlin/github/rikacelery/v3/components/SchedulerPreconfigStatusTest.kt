package github.rikacelery.v3.components

import github.rikacelery.v3.api.ApiClient
import github.rikacelery.v3.core.DataChannel
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.data.RoomSettings
import github.rikacelery.v3.data.RuntimeTuning
import github.rikacelery.v3.events.CommandAck
import github.rikacelery.v3.events.CommandEnvelope
import github.rikacelery.v3.events.GetPreconfiguringRoomIds
import github.rikacelery.v3.events.GetRoomConfig
import github.rikacelery.v3.events.RoomConfigResponse
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
 * The dashboard has to tell "the room is resolving its stream" apart from "the room is recording",
 * because Recording is only truthful once preconfiguration succeeded — and the session does not
 * even exist before that. The scheduler is the component that knows, so it is the one that answers
 * [GetPreconfiguringRoomIds].
 */
class SchedulerPreconfigStatusTest {

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `a room is reported as preconfiguring only while its stream is being resolved`() =
        runTest(UnconfinedTestDispatcher()) {
            val settings = RoomSettings(pkey = "streamKey")
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
                assertEquals(
                    emptyList<Long>(),
                    preconfiguring(requestBus),
                    "an armed room that is still waiting for the show is not preconfiguring"
                )

                scheduler.tell(SchedulerSignal(42, SchedulerEvent.BeginPreconfig, null))
                advanceUntilIdle()
                entry.preconfigLoop.cancel()   // this test drives the state, not the probe
                assertEquals(SchedulerState.Preconfiguring, entry.fsm.currentState)
                assertEquals(
                    listOf(42L),
                    preconfiguring(requestBus),
                    "the dashboard must be able to see that the room is still resolving its stream"
                )

                scheduler.tell(
                    SchedulerSignal(
                        42,
                        SchedulerEvent.PreconfigDone,
                        SchedulerDriveData(playlistUrl = "http://127.0.0.1:1/playlist.m3u8", quality = "720p")
                    )
                )
                advanceUntilIdle()
                assertEquals(SchedulerState.Recording, entry.fsm.currentState)
                assertEquals(
                    emptyList<Long>(),
                    preconfiguring(requestBus),
                    "recording begins only after preconfig succeeded, so the room is no longer preconfiguring"
                )

                scheduler.tell(SchedulerSignal(42, SchedulerEvent.BackToArmed, null))
                advanceUntilIdle()
                assertEquals(emptyList<Long>(), preconfiguring(requestBus))
            } finally {
                scheduler.stop()
            }
        }

    private suspend fun preconfiguring(requestBus: RequestBus): List<Long> =
        requestBus.request<List<Long>>(GetPreconfiguringRoomIds)
}
