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

    /**
     * A second, independent permit for frames the user is looking at *right now*.
     *
     * The timeline fills itself in with a background sweep, and those extractions
     * would otherwise occupy every permit and put a hover preview behind up to
     * [FRAME_CONCURRENCY] seeks of queue — a third of a second of pure latency on
     * the one request that has a human waiting on it. The priority lane is strictly
     * additive (one more process at most), never a reordering of the shared lane,
     * so a burst of hovers cannot starve the background sweep either.
     */
    private val framePriorityPermits = Semaphore(1)

    /**
     * On-disk frame timestamps per `<mediaId>-<width>`, keyed in tenths of a second.
     *
     * `cachedFrameTimes` answers the timeline's "what is already local" marks, and
     * every editor open asks for it. Deriving it from a directory walk alone is fine
     * at first but grows with the cache the user builds up, so a listing is cached and
     * later refined by the frames this process produces.
     */
    private val cachedTimes = ConcurrentHashMap<String, MutableSet<Long>>()

    private val indexStamp = ConcurrentHashMap<String, Long>()

    /**
     * Frames a prewarm sweep has already been asked for, so repeated scrolling over the
     * same ground does not queue the same extraction again and again.
     */
    private val requestedFrames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** True the first time this frame is scheduled; later sweeps skip it. */
    private fun rememberRequested(key: String): Boolean {
        if (requestedFrames.size > REQUESTED_FRAMES_MAX) requestedFrames.clear()
        return requestedFrames.add(key)
    }

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

    /**
     * A single still frame, used for precise trimming and timeline thumbnails.
     *
     * [priority] puts the request in front of the background sweep: see
     * [framePriorityPermits]. Only the client's hover preview asks for it.
     */
    suspend fun frame(entry: MediaEntry, source: File, atSeconds: Double, width: Int, priority: Boolean = false): ByteArray? {
        val rounded = snapFrameTime(atSeconds)
        val key = "${entry.id}-${width}-${Math.round(rounded * 10)}"
        frameCache[key]?.let { return it }
        frameWarmCache[key]?.let { return it }
        while (true) {
            frameInFlight[key]?.let { return it.await() }
            val fresh = CompletableDeferred<ByteArray?>()
            if (frameInFlight.putIfAbsent(key, fresh) == null) {
                try {
                    val semaphore = if (priority) framePriorityPermits else framePermits
                    val bytes = semaphore.withPermit {
                        // A hover frame may have been produced while this waited.
                        frameCache[key] ?: frameWarmCache[key] ?: withContext(Dispatchers.IO) {
                            runCatching { extractFrame(source, rounded, width, key) }.getOrNull()
                        }
                    }
                    if (bytes != null) {
                        frameCache[key] = bytes
                        if (priority) rememberWarmFrame(key, bytes)
                    }
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

    /**
     * The timestamps on the [step] grid that cover `[fromSeconds, toSeconds]`.
     *
     * Indices are exact by construction — `index * step` where the step is a multiple
     * of [FRAME_BUCKET_SECONDS] — which is what lets the client line a level up with
     * the cache instead of asking for 12.300000000000001 and missing 12.3.
     */
    fun frameGrid(fromSeconds: Double, toSeconds: Double, stepSeconds: Double, cap: Int = MAX_STRIP_FRAMES): List<Double> {
        val step = snapFrameTime(stepSeconds).coerceAtLeast(FRAME_BUCKET_SECONDS)
        // Index arithmetic, not a division with a fudge factor: the grid is a *set of
        // whole buckets*, so the ends are found by counting buckets rather than by
        // dividing and nudging, which would swallow a cell once the step is large.
        val stride = Math.max(1L, Math.round(step / FRAME_BUCKET_SECONDS))
        val first = Math.max(0L, Math.round(fromSeconds / FRAME_BUCKET_SECONDS))
        val last = Math.max(first, Math.round(toSeconds / FRAME_BUCKET_SECONDS))
        val count = ((last - first) / stride + 1).toInt()
        if (count <= 0) return emptyList()
        return (0 until minOf(count, cap)).map { i -> snapFrameTime((first + i * stride) * FRAME_BUCKET_SECONDS) }
    }

    /**
     * Produces the frames of one timeline level so later `/frame` reads are cache hits.
     *
     * Runs in the server's scope rather than the request's: the client is handed the
     * grid and hangs up immediately, and a cancelled request must not leave a sweep
     * half done. Frames already on disk, or already scheduled, are skipped, so a client
     * that scrolls quickly queues each distinct frame once and no more.
     */
    suspend fun prewarm(entry: MediaEntry, source: File, times: List<Double>, width: Int) {
        for (at in times) {
            val key = "${entry.id}-${width}-${Math.round(snapFrameTime(at) * 10)}"
            if (!rememberRequested(key)) continue
            // One unreadable instant must not abandon the rest of the level: the client
            // asked for a whole screenful and will simply draw around a gap.
            runCatching { frame(entry, source, at, width) }
        }
    }

    /**
     * The time grid every frame request is snapped to, in seconds.
     *
     * Both the frame cache key and the on-disk file name quantise to a tenth of a
     * second, so a request for 12.34 s and one for 12.36 s are the same frame. The
     * timeline subdivides by halving, so as long as it steps on multiples of this
     * bucket every level lands exactly on a cache entry instead of a near miss.
     */
    fun frameBucketSeconds(): Double = FRAME_BUCKET_SECONDS

    /** Snaps a timestamp onto the frame cache grid. */
    fun snapFrameTime(seconds: Double): Double =
        Math.round(seconds / FRAME_BUCKET_SECONDS) * FRAME_BUCKET_SECONDS

    private fun rememberWarmFrame(key: String, bytes: ByteArray) {
        frameWarmCache[key] = bytes
    }

    /**
     * One JPEG for one timestamp, straight from the source.
     *
     * `-ss` sits before `-i`, so this is an input seek: ffmpeg jumps to the
     * keyframe at or before the requested time instead of decoding from the start of
     * a multi-gigabyte file. Its output timestamps start at zero, which is what makes
     * the frame land at the timestamp the caller asked for rather than a GOP early.
     */
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
        if (!isUsable(out)) return null
        // Keeps the "this timestamp is already local" marks honest for frames this
        // session produced, without waiting for the next directory walk.
        indexCachedTime(out.name)
        return out.readBytes()
    }

    /** Adds one `<mediaId>-<width>-<tenths>.jpg` file name to the cached index. */
    private fun indexCachedTime(fileName: String) {
        val parts = fileName.removeSuffix(".jpg").split('-')
        if (parts.size < 3) return
        val tenths = parts.last().toLongOrNull() ?: return
        val width = parts[parts.size - 2].toIntOrNull() ?: return
        val mediaId = parts.subList(0, parts.size - 2).joinToString("-")
        cachedTimes.computeIfAbsent("$mediaId-$width") { ConcurrentHashMap.newKeySet() }.add(tenths)
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
    fun cachedFrameTimes(mediaId: String, width: Int): List<Double> {
        val key = "$mediaId-$width"
        val now = System.currentTimeMillis()
        cachedTimes[key]?.let { if (now - (indexStamp[key] ?: 0L) < FRAME_INDEX_TTL_MS) return it.sortedTimes() }
        val started = System.currentTimeMillis()
        val prefix = "$mediaId-$width-"
        val tenths = withFrameDir { dir ->
            dir.listFiles { f -> f.isFile && f.name.startsWith(prefix) && f.name.endsWith(".jpg") }
                ?.mapNotNull { it.name.removePrefix(prefix).removeSuffix(".jpg").toLongOrNull() }
                ?: emptyList()
        }
        // The stamp is taken *before* the listing and merged rather than replaced: a
        // frame produced while this walk ran must neither be dropped from the answer
        // nor make the next caller believe a complete listing sits behind this stamp.
        val index = cachedTimes.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() }
        index.addAll(tenths)
        indexStamp[key] = started
        return index.sortedTimes()
    }

    private fun MutableSet<Long>.sortedTimes(): List<Double> = sorted().map { it / 10.0 }

    private inline fun <T> withFrameDir(block: (File) -> T): T = block(frameDir())

    /** Delete cached previews for a recording. */
    fun evict(mediaId: String) {
        listOf("preview/$mediaId", "frames/$mediaId").forEach { rel ->
            val dir = File(config.cacheDir, rel)
            if (dir.isDirectory) dir.walkBottomUp().forEach { runCatching { it.delete() } }
        }
        // The in-memory copies would otherwise keep serving a deleted recording's
        // frames, and the cached-time index would keep claiming they are on disk.
        frameCache.keys.removeIf { it.startsWith("$mediaId-") }
        frameWarmCache.keys.removeIf { it.startsWith("$mediaId-") }
        cachedTimes.keys.removeIf { it.startsWith("$mediaId-") }
        indexStamp.keys.removeIf { it.startsWith("$mediaId-") }
        requestedFrames.removeIf { it.startsWith("$mediaId-") }
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

    /**
     * Bounded heap copies of recently produced frames.
     *
     * The timeline hover lane asks for the same frame again whenever the cursor comes
     * back over ground it has already covered; serving that from a directory read and
     * a response body is exactly the latency the cursor notices. A long scrub touches
     * hundreds of frames, though, so this cannot grow without bound — and a plain size
     * cap would evict the frame the cursor is about to return to. Access order gives a
     * one-line LRU instead.
     */
    private class FrameLru(private val max: Int) : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > max
    }

    /** Every frame this process has produced; see [FrameLru]. */
    private val frameCache = FrameLru(FRAME_MEMORY_CACHE_MAX)

    /** The hover lane's own pool, kept warmer and smaller than [frameCache]. */
    private val frameWarmCache = FrameLru(FRAME_WARM_CACHE_MAX)

    private companion object {
        const val TIMESTAMP_VERSION = "pts2"
        val CACHE_EXTENSIONS = listOf(".ts", ".png", ".jpg")

        /** Parallel frame extractions; each is a seek on the source file. */
        const val FRAME_CONCURRENCY = 4

        /**
         * The time grid every frame request snaps to, in seconds.
         *
         * The frame cache key and the on-disk file name both quantise to a tenth of a
         * second, so 12.34 s and 12.36 s are the same frame. The client subdivides the
         * timeline by halving from this bucket, so every level it asks for lands
         * exactly on a cache entry instead of a near miss next to one.
         */
        const val FRAME_BUCKET_SECONDS = 0.1

        /** Guard against one client asking for an entire recording in a single call. */
        const val MAX_STRIP_FRAMES = 240

        /** Tolerance for grid arithmetic, matching the cut engine's snap tolerance. */
        const val EPSILON = 1e-3

        /** Bound on the "already scheduled" set; clearing it only costs re-checks. */
        const val REQUESTED_FRAMES_MAX = 20_000

        /** Heap ceilings for the two frame pools. */
        const val FRAME_MEMORY_CACHE_MAX = 64
        const val FRAME_WARM_CACHE_MAX = 24

        /** How long a cached on-disk frame listing is trusted before it is re-walked. */
        const val FRAME_INDEX_TTL_MS = 1500L

        /**
         * Locale-independent number formatting. `String.format` with a default locale
         * can emit a decimal comma, which ffmpeg would parse as an argument
         * separator's worth of nonsense.
         */
        fun num(value: Double): String = String.format(Locale.ROOT, "%.3f", value)
    }
}
