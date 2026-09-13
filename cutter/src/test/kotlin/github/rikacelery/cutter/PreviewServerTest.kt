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
import kotlin.test.Test
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
}
