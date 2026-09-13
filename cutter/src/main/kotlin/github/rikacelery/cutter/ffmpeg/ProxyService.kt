package github.rikacelery.cutter.ffmpeg

import github.rikacelery.cutter.config.CutConfig
import github.rikacelery.cutter.media.MediaEntry
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class ProxyStatus { MISSING, RUNNING, READY, FAILED }

data class ProxyState(
    val mediaId: String,
    val status: ProxyStatus,
    val height: Int,
    /** 0..1, from ffmpeg's `-progress` stream. */
    val progress: Double = 0.0,
    val bytes: Long = 0,
    val error: String? = null
)

/**
 * Pre-rendered faststart proxy, played natively by the browser.
 *
 * ## Why this exists
 *
 * The point of a proxy is to let the *browser* decode and cache the video, so
 * scrubbing costs no server work at all. Pointing `<video>` at the recording
 * directly does not achieve that, because these files are **not faststart**: the
 * `moov` atom sits at the very end (measured: 24.2 MB at offset 6.39 GB of a 6.4 GB
 * recording), so the browser must pull the whole sample index before it can decode
 * a single frame — about **32 s** at the measured link speed, on every open.
 *
 * The proxy fixes that by writing a small faststart file once:
 *
 *   - `-movflags +faststart` puts the index at the front, so playback starts
 *     immediately and seeking is a range request;
 *   - the low resolution and CRF shrink it by an order of magnitude, so a six-hour
 *     recording becomes a few hundred MB that the browser can cache progressively;
 *   - decoding moves entirely to the client, which is what makes scrubbing smooth.
 *
 * Generation is a background job with progress; the result lives in the cache and is
 * evictable, since it is regenerable.
 */
class ProxyService(
    private val config: CutConfig,
    private val scope: CoroutineScope
) {
    private val log = LoggerFactory.getLogger(ProxyService::class.java)

    // Keyed by media *and* height: 240p and 480p are separate artefacts and both
    // may be generated at once, so a media-only key would let them report each
    // other's progress and cancel each other's job.
    private val states = ConcurrentHashMap<String, ProxyState>()
    private val jobs = ConcurrentHashMap<String, Job>()

    private fun key(mediaId: String, height: Int, fps: Int) = "$mediaId-$height-$fps"

    fun status(entry: MediaEntry, height: Int, fps: Int = DEFAULT_FPS): ProxyState {
        val k = key(entry.id, height, fps)
        val target = file(entry, height, fps)
        if (target.isFile && target.length() > 0) {
            val running = states[k]
            if (running == null || running.status != ProxyStatus.RUNNING) {
                return ProxyState(entry.id, ProxyStatus.READY, height, 1.0, target.length())
            }
        }
        return states[k] ?: ProxyState(entry.id, ProxyStatus.MISSING, height)
    }

    fun file(entry: MediaEntry, height: Int, fps: Int): File =
        File(Proc.ensureDir(File(config.cacheDir, "proxy")), "${entry.id}-${height}p${fps}fps.mp4")

    fun file(entry: MediaEntry, height: Int): File = file(entry, height, DEFAULT_FPS)

    /** Starts generation if it is not already running or done. */
    fun start(entry: MediaEntry, source: File, durationSeconds: Double?, height: Int, fps: Int = DEFAULT_FPS): ProxyState {
        val target = file(entry, height, fps)
        if (target.isFile && target.length() > 0) {
            return ProxyState(entry.id, ProxyStatus.READY, height, 1.0, target.length())
        }
        val k = key(entry.id, height, fps)
        if (jobs[k]?.isActive == true) {
            return states[k] ?: ProxyState(entry.id, ProxyStatus.RUNNING, height)
        }
        states[k] = ProxyState(entry.id, ProxyStatus.RUNNING, height, 0.0)
        jobs[k] = scope.launch(Dispatchers.IO) {
            val part = File(target.parentFile, "${target.name}.part")
            part.delete()
            try {
                val command = buildCommand(source, part, height, fps)
                log.info("proxy build start: {} -> {}p@{}fps (gpu={})", entry.fileName, height, fps, gpuEncoders)
                Proc.exec(
                    onStderr = { line -> if (line.isNotBlank()) log.trace("[proxy] {}", line) },
                    onStdout = { line ->
                        parseProgress(line, durationSeconds)?.let { fraction ->
                            states[k] = states[k]?.copy(progress = fraction) ?: return@let
                        }
                    },
                    cmd = command.toTypedArray()
                )
                if (part.isFile && part.length() > 0) {
                    if (!part.renameTo(target)) {
                        part.copyTo(target, overwrite = true)
                        part.delete()
                    }
                    states[k] = ProxyState(entry.id, ProxyStatus.READY, height, 1.0, target.length())
                    log.info("proxy ready: {} ({} MB)", target.name, target.length() / 1_048_576)
                } else {
                    states[k] = ProxyState(entry.id, ProxyStatus.FAILED, height, 0.0, error = "empty output")
                }
            } catch (e: Exception) {
                part.delete()
                states[k] = ProxyState(entry.id, ProxyStatus.FAILED, height, 0.0, error = e.message)
                log.warn("proxy build failed for {}: {}", entry.fileName, e.message)
            } finally {
                jobs.remove(k)
            }
        }
        return states[k]!!
    }

    /** Cancels generation of one resolution and deletes its artefact. */
    fun cancel(entry: MediaEntry, height: Int, fps: Int): Boolean {
        val k = key(entry.id, height, fps)
        jobs.remove(k)?.cancel()
        states.remove(k)
        file(entry, height, fps).let { if (it.isFile) it.delete() }
        return true
    }

    /** Deletes every generated resolution for a recording. */
    fun evictAll(entry: MediaEntry) = evictAll(entry.id)

    /**
     * Same as [evictAll] keyed by id, for callers that no longer hold the entry — a
     * deleted recording has left the index by the time its cache is swept.
     */
    fun evictAll(mediaId: String) {
        File(config.cacheDir, "proxy").listFiles { f -> f.name.startsWith("$mediaId-") }
            ?.forEach { runCatching { it.delete() } }
        states.keys.filter { it.startsWith("$mediaId-") }.forEach {
            states.remove(it)
            jobs.remove(it)?.cancel()
        }
    }

    /**
     * Encoder selection.
     *
     * Measured on the recording host for a six-hour 720p30 recording, 360p proxy:
     *
     *   CPU ultrafast ................ 8.7 min, 506 MB
     *   NVENC ........................ 9.5 min, 260 MB   (smaller, not faster)
     *   CUDA decode -> system memory . 14.6 min          (PCIe copy costs more than
     *                                                     the decode saves)
     *   CUDA decode kept on the GPU .. 7.8 min, 260 MB   (best)
     *
     * So the GPU only pays off when decoded frames stay on it and only the frames
     * that survive the `fps` drop are copied back. `-hwaccel cuda` *without*
     * `-hwaccel_output_format cuda` copies every decoded frame over PCIe and is
     * slower than doing it all on the CPU — an easy mistake to make.
     *
     * Everything degrades gracefully: if the container's ffmpeg has no NVENC the
     * same command is built with libx264.
     */
    private fun buildCommand(source: File, out: File, height: Int, fps: Int): List<String> {
        val gpu = gpuEncoders
        val video = if (gpu) {
            listOf("-c:v", "h264_nvenc", "-preset", "p1", "-tune", "ll", "-rc", "vbr", "-cq", "32", "-b:v", "0")
        } else {
            listOf("-c:v", "libx264", "-preset", "ultrafast", "-crf", "32")
        }
        val fpsFilter = if (fps > 0) "fps=$fps," else ""
        val vf = if (gpu) {
            // Drop frames on the GPU, then download only what is left.
            "${fpsFilter}hwdownload,format=nv12,scale=-2:$height"
        } else {
            "${fpsFilter}scale=-2:$height"
        }
        return buildList {
            add(config.ffmpeg); add("-hide_banner"); add("-v"); add("error")
            if (gpu) { add("-hwaccel"); add("cuda"); add("-hwaccel_output_format"); add("cuda") }
            add("-i"); add(source.absolutePath)
            add("-vf"); add(vf)
            addAll(video)
            // Pin the GOP to ~2 s *of output time*.
            //
            // Without this the encoder's default keyframe interval is 250 frames,
            // which at 1 fps is a 250-second GOP: rate control has nothing to measure
            // against and emits enormous frames. Measured on a 6-hour recording that
            // turned a 1 fps proxy into 1899 MB where 2 fps produced 119 MB — lower
            // frame rate yielding a larger file. Tying `-g` to the output rate keeps
            // size and seek granularity predictable at every setting.
            if (fps > 0) { add("-g"); add((fps * 2).toString()) }
            add("-pix_fmt"); add("yuv420p")
            add("-c:a"); add("aac"); add("-b:a"); add("64k"); add("-ac"); add("2")
            add("-f"); add("mp4")
            add("-movflags"); add("+faststart")
            add("-progress"); add("pipe:1"); add("-nostats")
            add("-y"); add(out.absolutePath)
        }
    }

    /** `-progress` emits `out_time_us=123456` lines; convert to a 0..1 fraction. */
    private fun parseProgress(line: String, durationSeconds: Double?): Double? {
        if (durationSeconds == null || durationSeconds <= 0) return null
        val trimmed = line.trim()
        if (!trimmed.startsWith("out_time_us=") && !trimmed.startsWith("out_time_ms=")) return null
        val micros = trimmed.substringAfter('=').toLongOrNull() ?: return null
        // out_time_ms is actually microseconds in ffmpeg's progress output.
        val seconds = micros / 1_000_000.0
        return (seconds / durationSeconds).coerceIn(0.0, 1.0)
    }

    /** `-version` of the container ffmpeg is probed once; NVENC present or not. */
    private val gpuEncoders: Boolean by lazy {
        val encoders = runCatching {
            ProcessBuilder(config.ffmpeg, "-hide_banner", "-encoders")
                .redirectErrorStream(true).start()
                .inputStream.bufferedReader().readText()
        }.getOrDefault("")
        val hasNvenc = "h264_nvenc" in encoders
        if (hasNvenc) log.info("proxy: NVENC available, using GPU encode + CUDA decode") else log.info("proxy: no NVENC, using libx264")
        hasNvenc
    }

    companion object {
        val DEFAULT_HEIGHT = 480
        /** Frames per second kept in the proxy; lower is much faster and smaller. */
        val DEFAULT_FPS = 5
        val ALLOWED_FPS = listOf(2, 5, 10, 0)
        fun label(height: Int): String = String.format(Locale.ROOT, "%dp", height)
    }
}
