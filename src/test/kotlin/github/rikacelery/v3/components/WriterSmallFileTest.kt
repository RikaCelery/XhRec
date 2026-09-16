package github.rikacelery.v3.components

import github.rikacelery.v3.core.DataChannel
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.data.DownloadMeta
import github.rikacelery.v3.data.StreamData
import github.rikacelery.v3.data.StreamEnd
import github.rikacelery.v3.data.StreamStart
import github.rikacelery.v3.events.EndReason
import github.rikacelery.v3.events.FileReady
import github.rikacelery.v3.events.WriterFatal
import github.rikacelery.v3.hooks.EventHook
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WriterSmallFileTest {

    @Test
    fun `zero threshold retains short textual recording with exact bytes`() = runTest(UnconfinedTestDispatcher()) {
        val input = "short recording".toByteArray()
        val tmpDir = Files.createTempDirectory("writer-small-file-").toFile()
        val (eventBus, published, ready) = setupEventCapture(1)
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

            assertContentEquals(input, ready.await().file.readBytes())
            assertEquals(1, published.filterIsInstance<FileReady>().single().roomId)
        } finally {
            writer.stop()
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `default threshold deletes 1023 byte recording without publishing`() = runTest(UnconfinedTestDispatcher()) {
        val tmpDir = Files.createTempDirectory("writer-small-file-").toFile()
        val (eventBus, published, roomTwoReady) = setupEventCapture(2)
        val dataChannel = DataChannel()
        val writer = WriterComponent(
            dataChannel = dataChannel,
            tmpDir = tmpDir,
            eventBus = eventBus,
            parentScope = this,
        )
        writer.start()

        try {
            sendRecording(dataChannel, ByteArray(1023) { it.toByte() })
            sendRecording(dataChannel, ByteArray(1024) { it.toByte() }, roomId = 2, roomName = "room-two")
            advanceUntilIdle()
            val roomTwoFile = roomTwoReady.await().file

            assertTrue(published.none { it is WriterFatal })
            assertTrue(published.filterIsInstance<FileReady>().none { it.roomId == 1L })
            assertEquals(setOf(roomTwoFile), tmpDir.listFiles()?.toSet() ?: emptySet())
        } finally {
            writer.stop()
            tmpDir.deleteRecursively()
        }
    }

    /**
     * The invariant behind the writer's lock: a write that loses the race against the close is
     * **refused**, not turned into an `IOException`. Reporting `WriterFatal` for it used to make
     * the scheduler drop the room's entry, so a platform frame that happened to land next to a cut
     * silently ended the room's recording life.
     */
    @Test
    fun `a write that arrives after the close is dropped instead of failing`() {
        val tmpDir = Files.createTempDirectory("writer-late-write-").toFile()
        val file = File(tmpDir, "room-init.mp4")
        val eventFile = File(tmpDir, "room-init.mp4.event")
        val active = ActiveFile(
            file = file,
            eventFile = eventFile,
            fos = FileOutputStream(file),
            eventFos = FileOutputStream(eventFile),
            roomId = 1,
            roomName = "room",
            startTime = Instant.parse("2026-01-01T00:00:00Z"),
            quality = "highest"
        )

        try {
            assertTrue(active.writeEvent("{\"type\":\"first\"}\n"), "a frame written before the close must land")
            active.closeStreams()

            assertFalse(active.writeEvent("{\"type\":\"late\"}\n"), "a late frame must be dropped")
            assertFalse(active.write(byteArrayOf(1, 2, 3)), "late media bytes must be dropped")
            assertEquals(0L, active.bytesWritten, "dropped bytes must not be counted as written")
            assertEquals("{\"type\":\"first\"}\n", eventFile.readText(), "the sidecar keeps only what landed")
        } finally {
            active.dispose()
            tmpDir.deleteRecursively()
        }
    }

    private fun setupEventCapture(targetRoomId: Long): Triple<EventBus, MutableList<Any>, CompletableDeferred<FileReady>> {
        val eventBus = EventBus()
        val published = mutableListOf<Any>()
        val targetReady = CompletableDeferred<FileReady>()
        eventBus.installHook(object : EventHook {
            override suspend fun intercept(event: Any): Any {
                published += event
                if (event is FileReady && event.roomId == targetRoomId) {
                    targetReady.complete(event)
                }
                return event
            }
        })
        return Triple(eventBus, published, targetReady)
    }

    private suspend fun sendRecording(
        dataChannel: DataChannel,
        input: ByteArray,
        roomId: Long = 1,
        roomName: String = "room"
    ) {
        val startTime = Instant.parse("2026-01-01T00:00:00Z")
        dataChannel.send(StreamStart(roomId, roomName, startTime, "highest"))
        dataChannel.send(
            StreamData(
                roomId = roomId,
                data = input,
                segmentIndex = 0,
                meta = DownloadMeta("test", 0, false, startTime)
            )
        )
        dataChannel.send(StreamEnd(roomId, EndReason.StreamEnd))
    }
}
