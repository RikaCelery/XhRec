package github.rikacelery.v3.components

import github.rikacelery.v3.api.ApiClient
import github.rikacelery.v3.core.DataChannel
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.events.GetArmedRoomIds
import github.rikacelery.v3.events.RoomRemoved
import github.rikacelery.v3.m3u8.M3u8Parser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import github.rikacelery.v3.data.RoomSettings

/**
 * Regression for issue #141: removing a room while it is armed/recording must
 * disarm the scheduler entry (and stop its recording session), instead of
 * leaving it running and producing the UI ghost-row state.
 */
class SchedulerRoomRemovalTest {

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `RoomRemoved disarms the scheduler entry`() = runTest(UnconfinedTestDispatcher()) {
        val eventBus = EventBus()
        val requestBus = RequestBus(eventBus, backgroundScope)
        val dataChannel = DataChannel()
        val downloader = DownloaderComponent(dataChannel, eventBus = eventBus, parentScope = backgroundScope)
        val session = SessionComponent(dataChannel, downloader, M3u8Parser, requestBus, eventBus, backgroundScope)
        val scheduler = SchedulerComponent(requestBus, session, ApiClient(), "streamKey", eventBus, backgroundScope)
        scheduler.start()
        advanceUntilIdle()

        try {
            scheduler.internalAdd(42, "model", RoomSettings(), isArmed = true)
            advanceUntilIdle()
            assertEquals(listOf(42L), requestBus.request<List<Long>>(GetArmedRoomIds))

            // room removed from the list (RemoveRoom command path publishes RoomRemoved)
            eventBus.publish(RoomRemoved(42, "model"))
            advanceUntilIdle()

            assertEquals(emptyList<Long>(), requestBus.request<List<Long>>(GetArmedRoomIds))
        } finally {
            scheduler.stop()
        }
    }
}
