package github.rikacelery.cutter.ffmpeg

import github.rikacelery.cutter.config.CutConfig
import github.rikacelery.cutter.media.MediaEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Locale

/** Which keyframe to snap a requested start to. */
enum class SnapMode { BEFORE, NEAREST, AFTER }

/**
 * Resolved export geometry for one segment.
 *
 * [actualStart] is the keyframe the cut really begins at. Copy mode cannot start on
 * an arbitrary frame, so the difference from [requestedStart] is reported rather than
 * hidden: measured on the corpus, cutting at a non-keyframe time produced 31.936 s
 * where 30 s was asked for, while cutting exactly on a keyframe produced 30.016 s.
 */
data class PlannedCut(
    val requestedStart: Double,
    val requestedEnd: Double,
    val actualStart: Double,
    val actualEnd: Double,
    val snapDelta: Double,
    val outputName: String
)

/**
 * Lossless (stream copy) cutting.
 *
 * ## There is no re-encode path
 *
 * Every exported byte is copied from the source. [buildCutCommand] is asserted
 * against a list of encoder flags by [assertNoEncoding] so that a future edit cannot
 * quietly introduce one.
 *
 * ## Keyframe accuracy
 *
 * `ffprobe -read_intervals` restricts the scan to a few seconds around the target,
 * which is what makes this affordable: a bounded probe of an 85-minute 1.2 GB file
 * costs ~0.15 s, where listing every keyframe costs ~8 s.
 */
class CutEngine(private val config: CutConfig) {

    private val log = LoggerFactory.getLogger(CutEngine::class.java)

    /**
     * Keyframe timestamps around [atSeconds], from a bounded probe.
     *
     * `-skip_frame nokey` makes the decoder emit only keyframes, which is far cheaper
     * than walking every packet.
     */
    suspend fun keyframesAround(source: File, atSeconds: Double, windowSeconds: Double = 4.0): List<Double> =
        keyframesIn(source, (atSeconds - windowSeconds).coerceAtLeast(0.0), atSeconds + windowSeconds)

    /** All keyframes in a range, for drawing ticks on the timeline. */
    /**
     * Keyframe timestamps inside a range.
     *
     * Reads **packet flags**, not decoded frames. The obvious command —
     * `-skip_frame nokey -show_entries frame=pts_time` — silently returns nothing on
     * the ffprobe 4.4.2 that ships in the container image (it worked on the host's
     * 8.1.2, which is how it went unnoticed): every keyframe query came back empty,
     * so both the timeline ticks and, worse, the cut engine's keyframe snapping were
     * quietly doing nothing and every cut fell back to ffmpeg's own backup-to-previous
     * -keyframe behaviour. Packet flags are index metadata, so this is also cheaper
     * than decoding, and it is what LosslessCut itself does (`flags[0] == 'K'`).
     */
    suspend fun keyframesIn(source: File, fromSeconds: Double, toSeconds: Double): List<Double> =
        withContext(Dispatchers.IO) {
            val from = fromSeconds.coerceAtLeast(0.0)
            val span = (toSeconds - from).coerceAtLeast(0.1)
            val command = listOf(
                config.ffprobe, "-v", "error",
                "-read_intervals", "${num(from)}%+${num(span)}",
                "-select_streams", "v:0",
                "-show_entries", "packet=pts_time,flags",
                "-of", "csv=p=0",
                source.absolutePath
            )
            runCatching {
                parseKeyframePackets(Proc.capture(*command.toTypedArray()))
            }.getOrElse {
                log.warn("keyframe probe failed {}-{}s: {}", from, toSeconds, it.message)
                emptyList()
            }
        }

    /**
     * Parses `packet=pts_time,flags` CSV into sorted keyframe timestamps.
     *
     * Rows look like `11972.066667,K_` (keyframe) or `11972.100000,__`. Kept separate
     * from the process call so the wire format has a test — the previous
     * implementation silently returned nothing for months' worth of ffprobe versions
     * because nothing pinned what the command was supposed to emit.
     */
    fun parseKeyframePackets(csv: String): List<Double> =
        csv.lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(',')
                if (parts.size < 2 || !parts[1].startsWith("K")) return@mapNotNull null
                parts[0].toDoubleOrNull()
            }
            .sorted()
            .toList()

    fun snap(keyframes: List<Double>, requested: Double, mode: SnapMode): Double {
        if (keyframes.isEmpty()) return requested
        return when (mode) {
            SnapMode.BEFORE -> keyframes.lastOrNull { it <= requested + EPSILON } ?: keyframes.first()
            SnapMode.AFTER -> keyframes.firstOrNull { it >= requested - EPSILON } ?: keyframes.last()
            SnapMode.NEAREST -> keyframes.minByOrNull { kotlin.math.abs(it - requested) } ?: requested
        }
    }

    /** Plans a single cut, resolving its keyframe and picking a free output name. */
    suspend fun plan(
        entry: MediaEntry,
        source: File,
        start: Double,
        end: Double,
        mode: SnapMode,
        cutNumber: Int
    ): PlannedCut {
        val frames = keyframesAround(source, start)
        val actualStart = snap(frames, start, mode).coerceAtLeast(0.0)
        val (from, to) = if (actualStart <= end) actualStart to end else start to end
        return PlannedCut(
            requestedStart = start,
            requestedEnd = end,
            actualStart = from,
            actualEnd = to,
            snapDelta = from - start,
            outputName = outputName(entry, from, to, cutNumber)
        )
    }

    /**
     * Output name, matching the convention already used by the 420 timestamped cut
     * files in the corpus: `<source>-<in>-<out>-cutN.mp4` with dotted timecodes.
     */
    fun outputName(entry: MediaEntry, start: Double, end: Double, cutNumber: Int): String {
        val base = entry.fileName.removeSuffix(".mp4")
        return "$base-${timecode(start)}-${timecode(end)}-cut$cutNumber.mp4"
    }

    /** `HH.MM.SS.mmm`, the timecode style used by the existing corpus. */
    fun timecode(seconds: Double): String {
        val total = seconds.coerceAtLeast(0.0)
        val h = (total / 3600).toInt()
        val m = ((total % 3600) / 60).toInt()
        val s = (total % 60).toInt()
        val ms = Math.round((total - Math.floor(total)) * 1000).toInt().coerceIn(0, 999)
        return String.format(Locale.ROOT, "%02d.%02d.%02d.%03d", h, m, s, ms)
    }

    /**
     * Picks the next free `-cutN` number for a source.
     *
     * Numbering restarts at 1 per recording, which is what the corpus does (42 of the
     * sourced files use exactly `(1)`, 27 use `(1,2)`). Existing files are skipped so
     * re-cutting never overwrites.
     */
    fun nextCutNumber(outputDir: File, entry: MediaEntry): Int {
        val base = entry.fileName.removeSuffix(".mp4")
        val existing = runCatching {
            outputDir.listFiles { f -> f.name.startsWith("$base-") && f.name.contains("-cut") }
                ?.mapNotNull { CUT_NUMBER.find(it.name)?.groupValues?.get(1)?.toIntOrNull() }
                ?.toSet() ?: emptySet()
        }.getOrDefault(emptySet())
        var n = 1
        while (n in existing) n++
        return n
    }

    /**
     * The export command.
     *
     * `-ss` before `-i` with `-c copy` makes ffmpeg start at the keyframe at or before
     * the requested time, which is why [plan] resolves that keyframe explicitly and
     * passes its exact timestamp: the segment then begins precisely where the user
     * said, instead of up to a GOP earlier.
     */
    fun buildCutCommand(source: File, output: File, start: Double, duration: Double, hasAudio: Boolean): List<String> {
        val command = mutableListOf(
            config.ffmpeg, "-hide_banner", "-v", "error",
            "-ss", num(start),
            "-i", source.absolutePath,
            "-t", num(duration),
            // Present because the cut starts mid-file; make_zero rebases the first
            // timestamp so the output does not claim to start hours in.
            "-avoid_negative_ts", "make_zero",
            "-map", "0:v:0", "-c:v", "copy"
        )
        if (hasAudio) {
            command += listOf("-map", "0:a:0", "-c:a", "copy")
        }
        command += listOf(
            "-map_metadata", "0",
            "-movflags", "+faststart",
            "-f", "mp4",
            "-y", output.absolutePath
        )
        assertNoEncoding(command)
        return command
    }

    /**
     * Refuses any command that would re-encode.
     *
     * Codec options are allowed only with the value `copy`; anything that implies
     * pixel work at all (filters, quality knobs, encoder names) is rejected outright.
     */
    fun assertNoEncoding(command: List<String>) {
        for (i in command.indices) {
            if (command[i] in CODEC_OPTIONS) {
                val value = command.getOrNull(i + 1)
                require(value == "copy") { "refusing to run a re-encoding cut: ${command[i]} $value" }
            }
        }
        val offender = command.firstOrNull { arg -> FORBIDDEN_ANYWHERE.any { arg == it } }
        require(offender == null) { "refusing to run a re-encoding cut: $offender" }
    }

    suspend fun runCut(command: List<String>, onLine: (String) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                Proc.exec(onStderr = { line -> if (line.isNotBlank()) onLine(line) }, cmd = command.toTypedArray())
            }.onFailure { log.error("cut failed: {}", it.message) }.isSuccess
        }

    /**
     * Writes the `.llc` alongside the existing corpus convention (`<source>-proj.llc`).
     * Written via a temporary file so an interrupted save cannot truncate a project.
     */
    suspend fun writeLlc(directory: File, name: String, text: String): File = withContext(Dispatchers.IO) {
        Proc.ensureDir(directory)
        val target = File(directory, name)
        val tmp = File(directory, "$name.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        target
    }

    private companion object {
        const val EPSILON = 1e-3

        val CUT_NUMBER = Regex("""-cut(\d+)\.mp4$""")

        /** Codec selectors, which are only ever acceptable with the value `copy`. */
        val CODEC_OPTIONS = listOf(
            "-c", "-c:v", "-c:a", "-codec", "-codec:v", "-codec:a", "-vcodec", "-acodec"
        )

        /** Flags that imply re-encoding no matter what they are set to. */
        val FORBIDDEN_ANYWHERE = listOf(
            "-crf", "-preset", "-tune", "-vf", "-filter:v", "-filter_complex", "-filter:a",
            "-b:v", "-b:a", "-q:v", "-q:a", "-pix_fmt",
            "libx264", "libx265", "libvpx", "libvpx-vp9", "mpeg4", "h264_nvenc", "h264_vaapi"
        )

        fun num(value: Double): String = String.format(Locale.ROOT, "%.3f", value)
    }
}
