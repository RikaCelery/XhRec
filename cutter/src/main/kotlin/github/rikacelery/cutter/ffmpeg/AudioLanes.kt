package github.rikacelery.cutter.ffmpeg

import github.rikacelery.cutter.config.CutConfig
import github.rikacelery.cutter.media.MediaEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Audio level and spectrogram images for the timeline.
 *
 * Audio is the strongest available reference for judging a recording, so both a
 * level (waveform) image and an opt-in spectrogram are offered.
 *
 * ## Measured cost on the recording host
 *
 * (85 min / 1.2 GB source, AAC-LC 48 kHz stereo, six cores)
 *
 * | operation                        | 60 s window | 85 min full | 6 h full | output |
 * |----------------------------------|-------------|-------------|----------|--------|
 * | raw decode (the floor)           | 0.104 s     | ~3.2 s      | ~13.5 s  | —      |
 * | level, 2000x300                  | 0.10 s      | 3.4 s       | 13.5 s   | 21 KB  |
 * | level, 32000x600 (overview)      | —           | 3.7 s       | ~14 s    | 280 KB |
 * | spectrogram, 1024x256            | 0.18 s      | ~2.4 s      | ~9.5 s   | 531 KB |
 * | spectrogram, 1920x512            | 0.37 s      | 4.7 s       | ~19 s    | 2.0 MB |
 *
 * Two conclusions shape the design:
 *
 *  - The level image is **decode-bound and already at the floor** (0.118 s without
 *    resampling vs 0.104 s for a bare decode). Widening the output is nearly free —
 *    2000px to 32000px costs +0.3 s — so one wide overview PNG is cached per
 *    recording and the browser crops and scales it for every coarse zoom level at no
 *    server cost.
 *  - The spectrogram is **filter-bound and scales with output pixels**, so it is
 *    generated only for the window being viewed, at 1024x256 by default, and never
 *    automatically for a whole recording.
 *
 * ## The trap this class exists to avoid
 *
 * `showwavespic` and `showspectrumpic` consume the *entire* input before emitting
 * their single frame, so `-t` placed **after** `-i` is silently ignored: `-t 1` and
 * `-t 600` were measured to produce byte-identical PNGs (md5 `d47974890d21b465`).
 * Every seek and duration here is therefore passed **before** `-i`.
 */
class AudioLanes(
    private val config: CutConfig
) {
    private val log = LoggerFactory.getLogger(AudioLanes::class.java)
    private val permits = Semaphore(2)
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<File>>()

    /**
     * The cached whole-file level overview, generating it on first use.
     *
     * Width is chosen from the duration (~2.2 px per second, capped at 32000) so that
     * even a nine-hour recording lands under the cap while still resolving a couple of
     * seconds per pixel at full zoom-out.
     */
    suspend fun overview(entry: MediaEntry, source: File, durationSeconds: Double): File? {
        val width = (durationSeconds * 2.2).toInt().coerceIn(1200, 32000)
        val target = File(dir(entry), "overview-$width.png")
        if (isUsable(target)) return target
        return produce("${entry.id}-ov-$width", target) {
            val command = listOf(
                config.ffmpeg, "-hide_banner", "-v", "error",
                "-i", source.absolutePath,
                "-filter_complex", "[0:a]aresample=$RESAMPLE,$NORMALIZE,$WAVE_FILTER_PREFIX${width}x$OVERVIEW_HEIGHT$WAVE_FILTER_SUFFIX",
                "-frames:v", "1",
                "-f", "image2",
                "-y", target.absolutePath
            )
            run(command)
        }
    }

    /** A level image for one window; cheap enough to regenerate while zooming. */
    suspend fun window(
        entry: MediaEntry,
        source: File,
        fromSeconds: Double,
        toSeconds: Double,
        width: Int = 2000
    ): File? {
        val duration = (toSeconds - fromSeconds).coerceAtLeast(0.2)
        val from = fromSeconds.coerceAtLeast(0.0)
        val w = width.coerceIn(200, 4000)
        val key = "${entry.id}-w-${round(from)}-${round(duration)}-$w"
        val target = File(dir(entry), "$key.png")
        if (isUsable(target)) return target
        return produce(key, target) {
            val command = listOf(
                config.ffmpeg, "-hide_banner", "-v", "error",
                // Input-side seek/duration: see the class comment.
                "-ss", num(from),
                "-t", num(duration),
                "-i", source.absolutePath,
                "-filter_complex", "[0:a]aresample=$RESAMPLE,$NORMALIZE,$WAVE_FILTER_PREFIX${w}x$WINDOW_HEIGHT$WAVE_FILTER_SUFFIX",
                "-frames:v", "1",
                "-f", "image2",
                "-y", target.absolutePath
            )
            run(command)
        }
    }

    /** Spectrogram for one window; opt-in because a PNG here is ~0.5 MB. */
    suspend fun spectrogram(
        entry: MediaEntry,
        source: File,
        fromSeconds: Double,
        toSeconds: Double,
        width: Int = 1024,
        height: Int = 256
    ): File? {
        val duration = (toSeconds - fromSeconds).coerceAtLeast(0.2)
        val from = fromSeconds.coerceAtLeast(0.0)
        val w = width.coerceIn(200, 4096)
        val h = height.coerceIn(128, 1024)
        val key = "${entry.id}-s-${round(from)}-${round(duration)}-$w-$h"
        val target = File(dir(entry), "$key.png")
        if (isUsable(target)) return target
        return produce(key, target) {
            val command = listOf(
                config.ffmpeg, "-hide_banner", "-v", "error",
                "-ss", num(from),
                "-t", num(duration),
                "-i", source.absolutePath,
                "-filter_complex", "[0:a]aresample=$RESAMPLE,$NORMALIZE,showspectrumpic=s=${w}x$h:legend=0:scale=log:fscale=log",
                "-frames:v", "1",
                "-f", "image2",
                "-y", target.absolutePath
            )
            run(command)
        }
    }

    /** Removes every audio image for a recording. */
    fun evict(mediaId: String) {
        val dir = File(config.cacheDir, "audio/$mediaId")
        if (dir.isDirectory) dir.walkBottomUp().forEach { runCatching { it.delete() } }
    }

    private suspend fun produce(key: String, target: File, block: suspend () -> Unit): File? {
        while (true) {
            inFlight[key]?.let { return it.await().takeIf { f -> isUsable(f) } }
            val fresh = CompletableDeferred<File>()
            if (inFlight.putIfAbsent(key, fresh) == null) {
                try {
                    permits.withPermit {
                        if (!isUsable(target)) block()
                    }
                    fresh.complete(target)
                    return target.takeIf { isUsable(it) }
                } catch (e: Throwable) {
                    fresh.completeExceptionally(e)
                    log.warn("audio lane generation failed for {}: {}", key, e.message)
                    return null
                } finally {
                    inFlight.remove(key)
                }
            }
        }
    }

    private suspend fun run(command: List<String>) = withContext(Dispatchers.IO) {
        Proc.ensureDir(File(command.last()).parentFile)
        log.debug("audio lane: {}", command.joinToString(" "))
        Proc.exec(
            onStderr = { line -> if (line.isNotBlank()) log.trace("[ffmpeg] {}", line) },
            cmd = command.toTypedArray()
        )
    }

    private fun isUsable(file: File): Boolean = file.isFile && file.length() > 0

    private fun dir(entry: MediaEntry): File = File(config.cacheDir, "audio/${entry.id}")

    private fun round(value: Double): Long = Math.round(value * 10)

    private companion object {
        /**
         * Speech and music content in these recordings carries nothing useful above
         * ~5 kHz, and resampling first cuts the filter cost roughly five-fold, so the
         * level image lands at the decode floor instead of above it.
         */
        const val RESAMPLE = 10000

        /**
         * Dynamic range normalization, so a quiet recording is visible at all.
         *
         * Without it a show recorded 20-30 dB down drew a flat line: `showwavespic` maps sample
         * values straight to pixels, so the level image was one faint band no matter how much
         * signal the audio carried. The same normalization is applied to the spectrogram, where a
         * quiet input otherwise renders as an almost black picture.
         */
        const val NORMALIZE = "dynaudnorm"

        const val WAVE_FILTER_PREFIX = "showwavespic=s="
        const val WAVE_FILTER_SUFFIX =
            ":scale=lin:filter=peak:split_channels=1:colors=#7dd3fc|#38bdf8"

        const val OVERVIEW_HEIGHT = 600
        const val WINDOW_HEIGHT = 300

        /** Locale-independent formatting; a decimal comma would corrupt ffmpeg args. */
        fun num(value: Double): String = String.format(Locale.ROOT, "%.3f", value)
    }
}
