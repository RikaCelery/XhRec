package github.rikacelery.cutter

import github.rikacelery.cutter.config.CutConfig
import github.rikacelery.cutter.ffmpeg.AudioLanes
import github.rikacelery.cutter.ffmpeg.Proc
import github.rikacelery.cutter.media.MediaEntry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Both audio lanes draw from normalized audio, and the spectrogram uses a log
 * frequency axis. These tests pin the two decisions down twice: once on the command
 * that is handed to ffmpeg, and once on the pixels that come back.
 */
class AudioLanesTest {
    @TempDir
    lateinit var root: File

    private fun entry(source: File) =
        MediaEntry("lane", source.name, source.name, "lane", source.length(), 0, null, 60, null)

    /**
     * A stand-in for ffmpeg that records its argv and leaves a file behind, so the
     * filter chain can be asserted without decoding anything.
     */
    private fun fakeFfmpeg(log: File): File {
        val script = File(root, "fake-ffmpeg.sh")
        script.writeText(
            """
            #!/bin/sh
            echo "${'$'}*" >> "${log.path}"
            prev=""
            for arg in "${'$'}@"; do
              if [ "${'$'}prev" = "-y" ]; then printf 'png' > "${'$'}arg"; fi
              prev="${'$'}arg"
            done
            """.trimIndent() + "\n"
        )
        script.setExecutable(true)
        return script
    }

    @Test
    fun `both lanes are normalized and the spectrogram uses a log frequency axis`() = runBlocking {
        val log = File(root, "argv.log")
        val ffmpeg = fakeFfmpeg(log)
        val config = CutConfig.parse(
            arrayOf("--cache", root.path, "--media", root.path, "--out", root.path, "--ffmpeg", ffmpeg.path)
        )
        val lanes = AudioLanes(config)
        val source = File(root, "source.mp4").apply { writeText("not really a video") }
        val media = entry(source)

        assertNotNull(lanes.overview(media, source, 600.0))
        assertNotNull(lanes.window(media, source, 30.0, 90.0))
        assertNotNull(lanes.spectrogram(media, source, 30.0, 90.0))

        val commands = log.readLines()
        assertEquals(3, commands.size, "one ffmpeg invocation per image")
        val overview = commands[0]
        val window = commands[1]
        val spectrogram = commands[2]

        assertTrue("dynaudnorm" in overview, "level overview must be normalized: $overview")
        assertTrue("dynaudnorm" in window, "level window must be normalized: $window")
        assertTrue("dynaudnorm" in spectrogram, "spectrogram must be normalized: $spectrogram")
        assertTrue("fscale=log" in spectrogram, "spectrogram must use a log frequency axis: $spectrogram")
        assertTrue("scale=log" in spectrogram, "spectrogram keeps its log intensity ramp: $spectrogram")

        // The documented trap: `showwavespic`/`showspectrumpic` consume the whole input
        // before emitting their frame, so -t after -i would be silently ignored and the
        // window images would cover the entire recording.
        for (command in listOf(window, spectrogram)) {
            val args = command.split(" ")
            assertTrue(args.indexOf("-t") < args.indexOf("-i"), "duration must precede the input: $command")
            assertTrue(args.indexOf("-ss") < args.indexOf("-i"), "seek must precede the input: $command")
            assertTrue("-ss 30.000" in command && "-t 60.000" in command, "window bounds are passed: $command")
        }

        // Second call: the versioned name is a cache hit, so nothing is re-rendered.
        assertNotNull(lanes.overview(media, source, 600.0))
        assertEquals(3, log.readLines().size, "a repeat request must be served from the cache")
    }

    @Test
    fun `a quiet recording is made visible by the normalization`() = runBlocking {
        assumeTrue(Proc.probeTool("ffmpeg") != null)
        val config = CutConfig.parse(arrayOf("--cache", root.path, "--media", root.path, "--out", root.path))
        val lanes = AudioLanes(config)
        val source = File(root, "quiet.m4a")
        // -30 dBFS of tone: the case that used to draw a flat line.
        Proc.exec(cmd = arrayOf(
            "ffmpeg", "-v", "error", "-f", "lavfi",
            "-i", "sine=frequency=440:sample_rate=48000:duration=60,volume=-12dB",
            "-c:a", "aac", "-b:a", "128k", "-y", source.path
        ))
        val image = assertNotNull(lanes.overview(entry(source), source, 60.0))

        // The same audio rendered without the normalization, as the reference point.
        val plain = File(root, "plain.png")
        Proc.exec(cmd = arrayOf(
            "ffmpeg", "-v", "error", "-i", source.path,
            "-filter_complex",
            "[0:a]aresample=10000,showwavespic=s=1200x600:scale=lin:filter=peak:split_channels=1:colors=#7dd3fc|#38bdf8",
            "-frames:v", "1", "-f", "image2", "-y", plain.path
        ))

        val normalized = meanLuma(image)
        val unnormalized = meanLuma(plain)
        assertTrue(
            normalized > unnormalized * 2,
            "normalized level image should carry far more signal: $normalized vs $unnormalized (of 255)"
        )
    }

    @Test
    fun `the log frequency axis moves a sweep up the image`() = runBlocking {
        assumeTrue(Proc.probeTool("ffmpeg") != null)
        val config = CutConfig.parse(arrayOf("--cache", root.path, "--media", root.path, "--out", root.path))
        val lanes = AudioLanes(config)
        val source = File(root, "sweep.m4a")
        // 100 Hz -> 4 kHz over 24 s, logarithmic by construction and below the 5 kHz
        // Nyquist of the 10 kHz resample, so nothing folds back into the picture.
        Proc.exec(cmd = arrayOf(
            "ffmpeg", "-v", "error", "-f", "lavfi",
            "-i", "aevalsrc=sin(2*PI*(100*24/log(40))*(40^(t/24)-1)):s=48000:d=24",
            "-ac", "1", "-c:a", "aac", "-b:a", "192k", "-y", source.path
        ))
        val logAxis = assertNotNull(lanes.spectrogram(entry(source), source, 0.0, 24.0, width = 600, height = 400))

        // The same audio on the linear axis ffmpeg defaults to.
        val linear = File(root, "linear.png")
        Proc.exec(cmd = arrayOf(
            "ffmpeg", "-v", "error", "-i", source.path,
            "-filter_complex", "[0:a]aresample=10000,showspectrumpic=s=600x400:legend=0:scale=log",
            "-frames:v", "1", "-f", "image2", "-y", linear.path
        ))

        // In the middle of the timeline the sweep sits between 340 Hz and 1.2 kHz. On a
        // linear axis that is the bottom fifth of the image, so the upper half of that
        // slice stays black; on a log axis the same tones land above the middle.
        val logMiddleTop = signalstats(logAxis, "crop=iw/3:ih/2:iw/3:0,signalstats")
        val linearMiddleTop = signalstats(linear, "crop=iw/3:ih/2:iw/3:0,signalstats")
        assertTrue(
            logMiddleTop > linearMiddleTop * 1.6 && logMiddleTop > 25.0,
            "the log axis should lift the sweep into the upper half: $logMiddleTop vs $linearMiddleTop on a linear axis"
        )
    }

    /** Mean luma of a PNG, 0..255. */
    private suspend fun meanLuma(image: File): Double = signalstats(image, "signalstats")

    private suspend fun signalstats(image: File, filter: String): Double {
        // `metadata=print` logs through av_log, i.e. stderr — the same channel the rest
        // of the pipeline reads ffmpeg's diagnostics from.
        val lines = StringBuilder()
        Proc.exec(
            onStderr = { line -> lines.appendLine(line) },
            cmd = arrayOf(
                "ffmpeg", "-v", "info", "-i", image.path, "-vf",
                "$filter,metadata=print:key=lavfi.signalstats.YAVG", "-f", "null", "-"
            )
        )
        return lines.lineSequence()
            .firstOrNull { "YAVG" in it }
            ?.substringAfter("YAVG=")
            ?.trim()
            ?.toDoubleOrNull()
            ?: error("no YAVG in ffmpeg output for $image")
    }
}
