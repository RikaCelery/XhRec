package github.rikacelery.v3.components

import github.rikacelery.v3.core.DataChannel
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.data.DownloadMeta
import github.rikacelery.v3.data.StreamData
import github.rikacelery.v3.data.StreamEnd
import github.rikacelery.v3.data.StreamStart
import github.rikacelery.v3.events.EndReason
import github.rikacelery.v3.events.FileReady
import github.rikacelery.v3.hooks.EventHook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WriterSmallFileTest {

    @Test
    fun `zero threshold retains short textual recording with exact bytes`() = runTest(UnconfinedTestDispatcher()) {
        val input = "short recording".toByteArray()
        val tmpDir = Files.createTempDirectory("writer-small-file-").toFile()
        val (eventBus, published) = setupEventCapture()
        val dataChannel = DataChannel()
        val writer = WriterComponent(
            dataChannel = dataChannel,
            tmpDir = tmpDir,
            eventBus = eventBus,
            parentScope = this,
            minOutputBytes = 0
        )
        writer.start()

        try {
            sendRecording(dataChannel, input)
            advanceUntilIdle()
            awaitWriter()

            val ready = published.filterIsInstance<FileReady>().single()
            assertContentEquals(input, ready.file.readBytes())
        } finally {
            writer.stop()
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `default threshold deletes 1023 byte recording without publishing`() = runTest(UnconfinedTestDispatcher()) {
        val tmpDir = Files.createTempDirectory("writer-small-file-").toFile()
        val eventBus = EventBus()
        val published = mutableListOf<Any>()
        eventBus.installHook(object : EventHook {
            override suspend fun intercept(event: Any): Any {
                published += event
                return event
            }
        })
        val dataChannel = DataChannel()
        val writer = WriterComponent(
            dataChannel = dataChannel,
            tmpDir = tmpDir,
            eventBus = eventBus,
            parentScope = this,
            minOutputBytes = 1024
        )
        writer.start()

        try {
            sendRecording(dataChannel, ByteArray(1023) { it.toByte() })
            advanceUntilIdle()
            awaitWriter()

            assertTrue(published.none { it is FileReady })
            assertEquals(emptyList(), tmpDir.listFiles()?.toList() ?: emptyList())
        } finally {
            writer.stop()
            tmpDir.deleteRecursively()
        }
    }

    private fun setupEventCapture(): Pair<EventBus, MutableList<Any>> {
        val eventBus = EventBus()
        val published = mutableListOf<Any>()
        eventBus.installHook(object : EventHook {
            override suspend fun intercept(event: Any): Any {
                published += event
                return event
            }
        })
        return eventBus to published
    }

    private suspend fun sendRecording(dataChannel: DataChannel, input: ByteArray) {
        val startTime = Instant.parse("2026-01-01T00:00:00Z")
        dataChannel.send(StreamStart(1, "room", startTime, "highest"))
        dataChannel.send(
            StreamData(
                roomId = 1,
                data = input,
                segmentIndex = 0,
                meta = DownloadMeta("test", 0, false, startTime)
            )
        )
        dataChannel.send(StreamEnd(1, EndReason.StreamEnd))
    }

    private suspend fun awaitWriter() {
        withContext(Dispatchers.IO) { Thread.sleep(100) }
    }
}
