package github.rikacelery.v3.components

import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.events.EndReason
import github.rikacelery.v3.events.FileReady
import github.rikacelery.v3.events.RecordingStopped
import github.rikacelery.v3.postprocessors.Processor
import github.rikacelery.v3.postprocessors.ProcessorCtx
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PostProcessorComponentTest {

    private class RecordingProcessor(
        val processed: MutableList<String> = mutableListOf()
    ) : Processor() {
        var delayMs: Long = 0

        override suspend fun process(input: File, ctx: ProcessorCtx): List<File> {
            if (delayMs > 0) delay(delayMs)
            processed.add("${ctx.roomName}:${input.name}")
            return listOf(input)
        }
    }

    private fun fileReady(roomId: Long, roomName: String, fileName: String) =
        FileReady(roomId, File("/tmp/$fileName"), EndReason.TimeLimit, roomName, 0, 0, 1000, "highest")

    @Test
    fun `single room single FileReady routes to processFile`() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus()
        val comp = PostProcessorComponent(bus, backgroundScope)
        val processor = RecordingProcessor()
        comp.setProcessors(listOf(processor))
        comp.start()

        bus.publish(fileReady(1, "roomA", "a.mp4"))
        advanceUntilIdle()

        assertEquals(listOf("roomA:a.mp4"), processor.processed)
        comp.stop()
    }

    @Test
    fun `single room multiple FileReady processed in arrival order`() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus()
        val comp = PostProcessorComponent(bus, backgroundScope)
        val processor = RecordingProcessor()
        comp.setProcessors(listOf(processor))
        comp.start()

        bus.publish(fileReady(1, "roomA", "1.mp4"))
        bus.publish(fileReady(1, "roomA", "2.mp4"))
        bus.publish(fileReady(1, "roomA", "3.mp4"))
        advanceUntilIdle()

        assertEquals(
            listOf("roomA:1.mp4", "roomA:2.mp4", "roomA:3.mp4"),
            processor.processed
        )
        comp.stop()
    }

    @Test
    fun `room A slow processing does not block room B dispatch`() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus()
        val comp = PostProcessorComponent(bus, backgroundScope, maxConcurrency = 2)
        val aStarted = CompletableDeferred<Unit>()
        val aCanFinish = CompletableDeferred<Unit>()
        val bProcessed = CompletableDeferred<String>()

        val processor = object : Processor() {
            override suspend fun process(input: File, ctx: ProcessorCtx): List<File> {
                if (ctx.roomId == 1L) {
                    aStarted.complete(Unit)
                    aCanFinish.await()
                } else {
                    bProcessed.complete(input.name)
                }
                return listOf(input)
            }
        }
        comp.setProcessors(listOf(processor))
        comp.start()

        bus.publish(fileReady(1, "roomA", "a.mp4"))
        bus.publish(fileReady(2, "roomB", "b.mp4"))

        // room B should have been dispatched and processed even though room A is blocked
        assertEquals("b.mp4", bProcessed.await())
        aCanFinish.complete(Unit)
        comp.stop()
    }

    @Test
    fun `room A FileReady queued while room A still processing`() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus()
        val comp = PostProcessorComponent(bus, backgroundScope)
        val started = CompletableDeferred<Unit>()
        val canFinish = CompletableDeferred<Unit>()
        val processed = mutableListOf<String>()

        val processor = object : Processor() {
            override suspend fun process(input: File, ctx: ProcessorCtx): List<File> {
                if (input.name == "first.mp4") {
                    started.complete(Unit)
                    canFinish.await()
                }
                processed.add("${ctx.roomName}:${input.name}")
                return listOf(input)
            }
        }
        comp.setProcessors(listOf(processor))
        comp.start()

        // send first file for roomA — starts processing, blocks on canFinish
        bus.publish(fileReady(1, "roomA", "first.mp4"))

        // send second file for roomA — must queue behind first
        bus.publish(fileReady(1, "roomA", "second.mp4"))

        // send file for roomB — goes to different channel, processes immediately
        bus.publish(fileReady(2, "roomB", "other.mp4"))

        // release roomA's first file
        canFinish.complete(Unit)
        advanceUntilIdle()

        // roomA: first.mp4 blocked on latch; roomB:other.mp4 gets 2nd permit and finishes
        // first.mp4 resumes after canFinish, then second.mp4
        assertEquals(
            listOf("roomB:other.mp4", "roomA:first.mp4", "roomA:second.mp4"),
            processed
        )
        comp.stop()
    }

    @Test
    fun `RecordingStopped cleans up idle RoomProcessor`() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus()
        val comp = PostProcessorComponent(bus, backgroundScope)
        val processor = RecordingProcessor()
        comp.setProcessors(listOf(processor))
        comp.start()

        bus.publish(fileReady(1, "roomA", "x.mp4"))
        advanceUntilIdle()

        // stop recording
        bus.publish(RecordingStopped(1))
        advanceUntilIdle()

        assertTrue(comp.jobs.isEmpty(), "jobs should be empty after cleanup")
        comp.stop()
    }

    @Test
    fun `new FileReady after cleanup recreates RoomProcessor`() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus()
        val comp = PostProcessorComponent(bus, backgroundScope)
        val processor = RecordingProcessor()
        comp.setProcessors(listOf(processor))
        comp.start()

        bus.publish(fileReady(1, "roomA", "first.mp4"))
        advanceUntilIdle()

        bus.publish(RecordingStopped(1))
        advanceUntilIdle()

        bus.publish(fileReady(1, "roomA", "second.mp4"))
        advanceUntilIdle()

        assertEquals(
            listOf("roomA:first.mp4", "roomA:second.mp4"),
            processor.processed
        )
        comp.stop()
    }
}
