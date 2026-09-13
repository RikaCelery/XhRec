package github.rikacelery.cutter.ffmpeg

import github.rikacelery.cutter.config.CutConfig
import github.rikacelery.cutter.media.MediaEntry
import github.rikacelery.cutter.media.MediaIndex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Quality presets for the preview transcode.
 *
 * The client reaches this host over a VPN at ~180 ms RTT, so the preview is
 * deliberately small: the point is to judge content, not to master it. Measured on
 * the recording host, a 4 s chunk of a 1.2 GB source transcodes in ~0.33 s, i.e.
 * roughly 12x realtime per worker.
 */
enum class PreviewQuality(
    val wire: String,
    val height: Int,
    val crf: Int,
    val audioKbps: Int
) {
    /** For a badly degraded link: a 4 s chunk lands around 8 KB. */
    TINY("tiny", 240, 36, 48),
    LOW("low", 360, 32, 64),
    MID("mid", 480, 28, 96),
    HIGH("high", 720, 26, 128);

    companion object {
        fun from(raw: String?): PreviewQuality = entries.firstOrNull { it.wire == raw } ?: LOW
    }
}

/**
 * On-demand HLS preview.
 *
 * Segments are transcoded the first time they are requested and then cached on local
 * disk, so scrubbing anywhere in a six-hour recording costs one small transcode and
 * every later visit to the same place is a plain file read. This is the piece
 * LosslessCut does not have to solve — it plays local files straight from disk, and
 * uses `file://` URLs — and it is what makes editing over a slow link practical.
 */
class PreviewServer(
    private val config: CutConfig,
    private val index: MediaIndex
) {
    private val log = LoggerFactory.getLogger(PreviewServer::class.java)

    /** Caps simultaneous ffmpeg preview processes; the host has six cores. */
    private val permits = Semaphore(config.previewConcurrency)

    /** Coalesces concurrent requests for the same segment into one transcode. */
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<File>>()

    private val frameInFlight = ConcurrentHashMap<String, CompletableDeferred<ByteArray?>>()

    /**
     * Frame extraction is cheap (~0.15 s) but each one seeks a multi-gigabyte file,
     * so a handful run in parallel — enough to fill the client's prefetch window,
     * not so many that concurrent seeks thrash the NAS.
     */
    private val framePermits = Semaphore(FRAME_CONCURRENCY)

    private val cacheBytes = AtomicLong(0)

    fun segmentCount(durationSeconds: Double): Int {
        val seg = config.previewSegmentSeconds
        return maxOf(1, Math.ceil(durationSeconds / seg).toInt())
    }

    /** VOD playlist describing every segment; no ffmpeg needed to build it. */
    fun playlist(entry: MediaEntry, durationSeconds: Double, quality: PreviewQuality): String {
        val seg = config.previewSegmentSeconds
        val count = segmentCount(durationSeconds)
        val sb = StringBuilder(256 + count * 32)
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:3\n")
        sb.append("#EXT-X-PLAYLIST-TYPE:VOD\n")
        sb.append("#EXT-X-TARGETDURATION:").append(seg).append('\n')
        sb.append("#EXT-X-MEDIA-SEQUENCE:0\n")
        for (i in 0 until count) {
            val start = i * seg.toDouble()
            val length = minOf(seg.toDouble(), durationSeconds - start).coerceAtLeast(0.1)
            sb.append("#EXTINF:").append(num(length)).append(",\n")
            // Relative to /api/media/{id}/, so this must repeat the `preview/`
            // prefix or the player would request /api/media/{id}/0.ts and 404.
            sb.append("preview/").append(i).append(".ts?q=").append(quality.wire)
                .append("&v=").append(TIMESTAMP_VERSION).append('\n')
        }
        sb.append("#EXT-X-ENDLIST\n")
        return sb.toString()
    }

    /** Cache file for a segment, transcoding it first when absent. */
    suspend fun segment(entry: MediaEntry, source: File, segIndex: Int, quality: PreviewQuality): File? {
        val seg = config.previewSegmentSeconds
        val start = segIndex * seg.toDouble()
        val key = "${entry.id}-${quality.wire}-$seg-${segIndex}"
        val target = File(cacheDir(entry, quality), "$segIndex.ts")
        if (isUsable(target)) return target

        while (true) {
            inFlight[key]?.let { return it.await() }
            val fresh = CompletableDeferred<File>()
            if (inFlight.putIfAbsent(key, fresh) == null) {
                try {
                    permits.withPermit {
                        // Re-check under the permit: another worker may have produced
                        // this segment while this request waited in the queue.
                        if (!isUsable(target)) transcode(source, start, seg.toDouble(), quality, target)
                    }
                    if (isUsable(target)) {
                        fresh.complete(target)
                        return target
                    }
                    fresh.completeExceptionally(IllegalStateException("segment produced nothing"))
                    return null
                } catch (e: Throwable) {
                    fresh.completeExceptionally(e)
                    throw e
                } finally {
                    inFlight.remove(key)
                }
            }
        }
    }

    private fun isUsable(file: File): Boolean = file.isFile && file.length() > 0

    private suspend fun transcode(
        source: File,
        start: Double,
        seconds: Double,
        quality: PreviewQuality,
        target: File
    ) = withContext(Dispatchers.IO) {
        Proc.ensureDir(target.parentFile)
        // Written to a temporary name and renamed, so a partially written segment can
        // never be served or mistaken for a cache hit.
        val tmp = File(target.parentFile, "${target.name}.part")
        tmp.delete()
        val command = buildTranscodeCommand(source, start, seconds, quality, tmp)
        log.debug("preview transcode: {}", command.joinToString(" "))
        try {
            Proc.exec(
                onStderr = { line -> if (line.isNotBlank()) log.trace("[ffmpeg] {}", line) },
                cmd = command.toTypedArray()
            )
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            cacheBytes.addAndGet(target.length())
        } catch (e: Exception) {
            tmp.delete()
            log.warn("preview transcode failed at {}s: {}", start, e.message)
            throw e
        }
    }

    /**
     * The `-ss` sits *before* `-i` on purpose. When transcoding, ffmpeg performs an
     * accurate seek from that position: it jumps to the preceding keyframe, then
     * decodes and discards up to the requested timestamp, so a segment starts exactly
     * where the playlist says it does. (With `-c copy` the same placement would snap
     * to the keyframe instead — which is why the cut engine probes keyframes itself.)
     */
    private fun buildTranscodeCommand(
        source: File,
        start: Double,
        seconds: Double,
        quality: PreviewQuality,
        out: File
    ): List<String> = listOf(
        config.ffmpeg, "-hide_banner", "-v", "error",
        "-ss", num(start),
        "-i", source.absolutePath,
        "-t", num(seconds),
        "-vf", "scale=-2:${quality.height}",
        "-c:v", "libx264",
        "-preset", "ultrafast",
        "-tune", "zerolatency",
        "-crf", quality.crf.toString(),
        "-pix_fmt", "yuv420p",
        // Guarantees the first frame is an IDR so HLS segment switching is clean.
        "-force_key_frames", "0",
        "-c:a", "aac",
        "-b:a", "${quality.audioKbps}k",
        "-ac", "2",
        // Independent ffmpeg invocations reset timestamps to zero. HLS requires
        // one shared timeline, otherwise every fragment overwrites the first four
        // seconds in MediaSource and far seeks jump back into that buffer.
        "-output_ts_offset", num(start),
        "-f", "mpegts",
        "-y", out.absolutePath
    )

    /** A single still frame, used for precise trimming and timeline thumbnails. */
    suspend fun frame(entry: MediaEntry, source: File, atSeconds: Double, width: Int): ByteArray? {
        val rounded = Math.round(atSeconds * 10) / 10.0
        val key = "${entry.id}-${width}-${Math.round(rounded * 10)}"
        frameCache[key]?.let { return it }
        while (true) {
            frameInFlight[key]?.let { return it.await() }
            val fresh = CompletableDeferred<ByteArray?>()
            if (frameInFlight.putIfAbsent(key, fresh) == null) {
                try {
                    val bytes = framePermits.withPermit {
                        withContext(Dispatchers.IO) { runCatching { extractFrame(source, rounded, width, key) }.getOrNull() }
                    }
                    if (bytes != null) frameCache[key] = bytes
                    fresh.complete(bytes)
                    return bytes
                } catch (e: Throwable) {
                    fresh.completeExceptionally(e)
                    throw e
                } finally {
                    frameInFlight.remove(key)
                }
            }
        }
    }

    private suspend fun extractFrame(source: File, atSeconds: Double, width: Int, key: String): ByteArray? {
        val out = File(Proc.ensureDir(frameDir()), "$key.jpg")
        if (!isUsable(out)) {
            val command = listOf(
                config.ffmpeg, "-hide_banner", "-v", "error",
                "-ss", num(atSeconds),
                "-i", source.absolutePath,
                "-frames:v", "1",
                "-vf", "scale=$width:-2",
                "-q:v", "4",
                "-f", "image2",
                "-y", out.absolutePath
            )
            Proc.exec(cmd = command.toTypedArray())
        }
        return if (isUsable(out)) out.readBytes() else null
    }

    private fun cacheDir(entry: MediaEntry, quality: PreviewQuality): File =
        File(config.cacheDir, "preview/${entry.id}/${quality.wire}-${config.previewSegmentSeconds}-$TIMESTAMP_VERSION")

    private fun frameDir(): File = File(config.cacheDir, "frames")

    /**
     * Timestamps (seconds) that already have a cached frame for this recording.
     *
     * Exposed so the timeline can mark what is already local, the way After Effects
     * shades cached frames — the cache survives restarts, so this is worth showing
     * rather than only reflecting the current browser session.
     */
    fun cachedFrameTimes(mediaId: String, width: Int): List<Double> = withFrameDir { dir ->
        val prefix = "$mediaId-$width-"
        dir.listFiles { f -> f.isFile && f.name.startsWith(prefix) && f.name.endsWith(".jpg") }
            ?.mapNotNull { file ->
                file.name.removePrefix(prefix).removeSuffix(".jpg").toLongOrNull()?.let { it / 10.0 }
            }
            ?.sorted()
            ?: emptyList()
    }

    private inline fun <T> withFrameDir(block: (File) -> T): T = block(frameDir())

    /** Delete cached previews for a recording. */
    fun evict(mediaId: String) {
        listOf("preview/$mediaId", "frames/$mediaId").forEach { rel ->
            val dir = File(config.cacheDir, rel)
            if (dir.isDirectory) dir.walkBottomUp().forEach { runCatching { it.delete() } }
        }
    }

    /**
     * Trim the cache back under its cap, oldest files first.
     *
     * The recording host's root filesystem has limited headroom, so the cache must
     * never grow without bound; everything in it is regenerable.
     */
    suspend fun enforceCacheLimit() = withContext(Dispatchers.IO) {
        val root = config.cacheDir
        if (!root.isDirectory) return@withContext
        val files = root.walkTopDown()
            .filter { it.isFile && CACHE_EXTENSIONS.any { ext -> it.name.endsWith(ext) } }
            .toList()
        val totalBytes = files.sumOf { it.length() }
        cacheBytes.set(totalBytes)
        if (totalBytes <= config.cacheLimitBytes) return@withContext
        var freed = 0L
        val excess = totalBytes - config.cacheLimitBytes
        for (file in files.sortedBy { it.lastModified() }) {
            if (freed >= excess) break
            val size = file.length()
            if (file.delete()) freed += size
        }
        log.info("cache trimmed: freed {} MB (cap {} MB)", freed / 1_048_576, config.cacheLimitBytes / 1_048_576)
    }

    private val frameCache = ConcurrentHashMap<String, ByteArray>()

    private companion object {
        const val TIMESTAMP_VERSION = "pts2"
        val CACHE_EXTENSIONS = listOf(".ts", ".png", ".jpg")

        /** Parallel frame extractions; each is a seek on the source file. */
        const val FRAME_CONCURRENCY = 4

        /**
         * Locale-independent number formatting. `String.format` with a default locale
         * can emit a decimal comma, which ffmpeg would parse as an argument
         * separator's worth of nonsense.
         */
        fun num(value: Double): String = String.format(Locale.ROOT, "%.3f", value)
    }
}
