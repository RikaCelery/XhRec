package github.rikacelery.cutter

import github.rikacelery.cutter.config.CutConfig
import github.rikacelery.cutter.ffmpeg.PreviewQuality
import github.rikacelery.cutter.ffmpeg.PreviewServer
import github.rikacelery.cutter.ffmpeg.Proc
import github.rikacelery.cutter.media.MediaEntry
import github.rikacelery.cutter.media.MediaIndex
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PreviewServerTest {
    @TempDir
    lateinit var root: File

    @Test
    fun `independent preview fragments retain source time for audio and video`() = runBlocking {
        assumeTrue(Proc.probeTool("ffmpeg") != null && Proc.probeTool("ffprobe") != null)
        val config = CutConfig.parse(arrayOf("--cache", root.path, "--media", root.path, "--out", root.path))
        val server = PreviewServer(config, MediaIndex(config))
        val source = File(root, "source.mp4")
        Proc.exec(cmd = arrayOf(
            "ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=160x90:rate=30",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
            "-t", "16", "-c:v", "libx264", "-preset", "ultrafast", "-g", "60",
            "-c:a", "aac", "-y", source.path
        ))
        val entry = MediaEntry("test", source.name, source.name, "test", source.length(), 0, null, 16, null)
        // A pre-fix cache must never be mistaken for a corrected segment.
        val old = File(root, "preview/test/low-4/1.ts")
        old.parentFile.mkdirs()
        old.writeText("old reset timestamps")
        val playlist = server.playlist(entry, 16.0, PreviewQuality.LOW)
        assertEquals(4, playlist.lineSequence().count { it.startsWith("#EXTINF:") })
        assertTrue(playlist.lineSequence().filter { it.startsWith("preview/") }.all { "&v=" in it })

        suspend fun firstPts(file: File, stream: String): Double = Proc.capture(
            "ffprobe", "-v", "error", "-select_streams", stream, "-show_packets",
            "-show_entries", "packet=pts_time", "-of", "csv=p=0", file.path
        ).lineSequence().first { it.isNotBlank() }.substringBefore(',').toDouble()

        val first = assertNotNull(server.segment(entry, source, 0, PreviewQuality.LOW))
        for (n in listOf(1, 3, 2)) { // Includes a distant seek and backwards loading.
            val fragment = assertNotNull(server.segment(entry, source, n, PreviewQuality.LOW))
            assertFalse(fragment == old)
            for (stream in listOf("v:0", "a:0")) {
                val delta = firstPts(fragment, stream) - firstPts(first, stream)
                assertTrue(abs(delta - n * 4.0) < 0.06, "$stream fragment $n starts $delta seconds after fragment 0")
            }
        }
    }

    /**
     * The timeline subdivides by halving and expects every level to land on the frame
     * cache's grid — a grid time one bucket away from a produced frame is not a visible
     * bug, it is a silent cache miss that re-encodes a frame the server already has.
     */
    @Test
    fun `frame grid lands exactly on the frame cache buckets`() {
        val config = CutConfig.parse(arrayOf("--cache", root.path, "--media", root.path, "--out", root.path))
        val server = PreviewServer(config, MediaIndex(config))
        val bucket = server.frameBucketSeconds()

        // The pyramid's steps: the cache bucket doubled level by level.
        for (level in 0..12) {
            val step = bucket * 2.0.pow(level)
            val from = 37.3
            val to = from + step * 4
            val grid = server.frameGrid(from, to, step)
            assertTrue(grid.min() >= from && grid.min() < from + step, "level $level starts outside its first cell")
            assertTrue(grid.max() <= to + bucket && grid.max() > to - step, "level $level ends outside its last cell")
            val buckets = grid.map { Math.round(it * 10) }
            val stride = Math.round(step / bucket).toInt()
            assertEquals(buckets.distinct().size, buckets.size, "level $level has two cells on one bucket")
            for ((i, t) in grid.withIndex()) {
                assertTrue(abs(t - Math.round(t * 10) / 10.0) < 1e-9, "level $level time $t is off the bucket grid")
                // A client that rebuilds the grid itself from the index — `index * step`
                // — must land on the same cache bucket, or it asks for a frame nobody
                // produced and the lane stays empty.
                val rebuilt = (buckets.first() + i * stride) * bucket
                assertEquals(Math.round(t * 10), Math.round(rebuilt * 10), "level $level index $i rebuilds to $rebuilt")
            }
        }
    }

    @Test
    fun `frame grid is bounded and clamps at the start of the recording`() {
        val config = CutConfig.parse(arrayOf("--cache", root.path, "--media", root.path, "--out", root.path))
        val server = PreviewServer(config, MediaIndex(config))
        val grid = server.frameGrid(0.0, 10_000.0, 0.1)
        assertEquals(240, grid.size, "one request must not sweep an entire recording")
        assertEquals(0.0, grid.first(), 1e-9)
        assertTrue(server.frameGrid(-30.0, 1.0, 0.5).all { it >= 0.0 }, "negative times are clamped away")
    }

    /**
     * The hover preview shares the extraction lanes with the timeline sweep but not its
     * queue, and asks for a different width than the sweep does. Both must produce the
     * same frame for the same instant, or the popup and the lane would disagree.
     */
    @Test
    fun `priority and background frame requests agree on the same frame`() = runBlocking {
        assumeTrue(Proc.probeTool("ffmpeg") != null)
        val config = CutConfig.parse(arrayOf("--cache", root.path, "--media", root.path, "--out", root.path))
        val server = PreviewServer(config, MediaIndex(config))
        val source = File(root, "grid.mp4")
        Proc.exec(cmd = arrayOf(
            "ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=10",
            "-t", "4", "-c:v", "libx264", "-preset", "ultrafast", "-g", "20", "-y", source.path
        ))
        val entry = MediaEntry("grid", source.name, source.name, "grid", source.length(), 0, null, 4, null)

        val background = assertNotNull(server.frame(entry, source, 2.0, 480))
        val priority = assertNotNull(server.frame(entry, source, 2.0, 480, priority = true))
        assertContentEquals(background, priority, "the two lanes must serve the same bytes")

        // A request that rounds onto the same bucket is a cache hit, not a second encode.
        assertContentEquals(background, assertNotNull(server.frame(entry, source, 2.04, 480)))
        assertTrue(server.frame(entry, source, 3.6, 480, priority = true)!!.isNotEmpty())

        val grid = server.frameGrid(0.0, 3.9, 0.5)
        server.prewarm(entry, source, grid, 160)
        val times = server.cachedFrameTimes(entry.id, 160)
        for (t in grid) {
            assertTrue(times.any { abs(it - t) < 1e-9 }, "prewarm should have produced the frame at $t (have $times)")
        }
    }
}
