package github.rikacelery.v3.components

import github.rikacelery.v3.api.ApiClient
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.data.Room
import github.rikacelery.v3.utils.DefaultHttpClientProvider
import github.rikacelery.v3.utils.runProcessGetStdout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The strip the WebUI scrubs through is built with ffmpeg, so these tests skip themselves where
 * ffmpeg is missing instead of failing a machine that has no video toolchain (the Docker image and
 * a developer box both ship it).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreviewComponentTest {

    private suspend fun ffmpegAvailable(): Boolean = try {
        runProcessGetStdout("ffmpeg", "-version").isNotEmpty()
    } catch (_: Exception) {
        false
    }

    /** Writes a real, decodable JPEG sample — the sprite pass has to be able to read it. */
    private suspend fun sample(dir: File, slot: Long) {
        dir.mkdirs()
        runProcessGetStdout(
            "ffmpeg", "-hide_banner", "-v", "error", "-y",
            "-f", "lavfi", "-i", "color=c=blue:s=480x270",
            "-frames:v", "1", File(dir, "$slot.jpg").absolutePath
        )
    }

    private suspend fun dimensions(file: File): String = runProcessGetStdout(
        "ffprobe", "-v", "error", "-select_streams", "v",
        "-show_entries", "stream=width,height", "-of", "csv=p=0", file.absolutePath
    )

    @Test
    fun `the sprite tiles every sample into one grid, oldest first`() = runTest {
        assumeTrue(ffmpegAvailable(), "ffmpeg is required to build the strip")
        val tmp = createTempDirectory("preview-test").toFile()
        val eventBus = EventBus()
        val component = PreviewComponent(
            RequestBus(eventBus, backgroundScope),
            ApiClient(listOf("example.invalid"), DefaultHttpClientProvider),
            eventBus,
            this,
            tmp,
            DefaultHttpClientProvider
        )
        try {
            // No start(): the sampler loop is not what is under test, and a live actor would keep the
            // test scope busy. The store half of the component is usable on its own.
            val dir = File(tmp, "preview/1001")
            val slots = listOf(1_700_000_000L, 1_700_000_300L, 1_700_000_600L, 1_700_000_900L)
            slots.forEach { sample(dir, it) }

            val sprite = assertNotNull(component.spriteInfo(1001L), "a strip needs samples")
            assertEquals(slots.size, sprite.samples.size)
            assertEquals(6, sprite.columns)
            assertEquals(4, sprite.rows)
            // Four samples padded out to the full grid: 6 columns x 4 rows of 320x180 cells is the
            // geometry the browser indexes into, so the image has to be exactly that size.
            assertEquals("1920,720", dimensions(sprite.file))

            val latest = assertNotNull(component.latest(1001L))
            assertEquals("${slots.last()}.jpg", latest.name)
        } finally {
            component.stop()
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `retention keeps the newest samples and drops the stale ones`() = runTest {
        val tmp = createTempDirectory("preview-prune").toFile()
        val eventBus = EventBus()
        val component = PreviewComponent(
            RequestBus(eventBus, backgroundScope),
            ApiClient(listOf("example.invalid"), DefaultHttpClientProvider),
            eventBus,
            this,
            tmp,
            DefaultHttpClientProvider
        )
        try {
            val dir = File(tmp, "preview/1001")
            dir.mkdirs()
            val now = System.currentTimeMillis()
            // 30 slots five minutes apart: the cap has to keep the newest 24 ...
            val slots = (0 until 30).map { now - it * 5 * 60 * 1000L }.sorted()
            slots.forEach { File(dir, "$it.jpg").writeBytes(byteArrayOf(0)) }
            // ... and one ancient file has to go even though it is inside the newest 24.
            val ancient = File(dir, "${now - 5 * 60 * 60 * 1000L}.jpg")
            ancient.writeBytes(byteArrayOf(0))

            component.prune(dir)

            val kept = dir.listFiles()!!.map { it.name }.sorted()
            assertEquals(24, kept.size)
            assertTrue(ancient.name !in kept, "a sample older than two hours is not preview material")
            assertTrue("${slots.last()}.jpg" in kept, "the newest sample must survive")
        } finally {
            component.stop()
            tmp.deleteRecursively()
        }
    }

    /**
     * Armed rooms are sampled so the strip is already populated when a broadcast starts — but only
     * the ones the platform last showed as public, so an armed room that is off costs no request.
     */
    @Test
    fun `sampling covers recording rooms and armed public ones`() {
        val rooms = listOf(
            Room(id = 1L, name = "recording-private", quality = "720p", sizeLimitBytes = 0, lastSeen = null, status = "virtualPrivate"),
            Room(id = 2L, name = "armed-public", quality = "720p", sizeLimitBytes = 0, lastSeen = null, status = "public"),
            Room(id = 3L, name = "armed-off", quality = "720p", sizeLimitBytes = 0, lastSeen = null, status = "off"),
            Room(id = 4L, name = "idle-public", quality = "720p", sizeLimitBytes = 0, lastSeen = null, status = "public")
        )
        val sessions = listOf(
            RoomSession(1L, "recording-private", "720p", SessionState.Recording, Instant.now()),
            RoomSession(4L, "idle-public", "720p", SessionState.Idle, Instant.now())
        )

        val targets = previewTargets(rooms, sessions, armed = setOf(2L, 3L))

        assertEquals(
            listOf(1L to "recording-private", 2L to "armed-public"),
            targets,
            "a recording room is covered whatever its status, an armed one only while public"
        )
    }

    @Test
    fun `a room that is not in the list cannot be sampled`() {
        val targets = previewTargets(
            rooms = listOf(Room(id = 1L, name = "known", quality = "720p", sizeLimitBytes = 0, lastSeen = null, status = "public")),
            sessions = emptyList(),
            armed = setOf(1L, 999L)
        )
        assertEquals(listOf(1L to "known"), targets, "no name means no broadcast to ask about")
    }
}
