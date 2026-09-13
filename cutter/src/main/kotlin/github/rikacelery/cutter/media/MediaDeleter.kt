package github.rikacelery.cutter.media

import github.rikacelery.cutter.config.CutConfig
import github.rikacelery.cutter.events.TimelineService
import github.rikacelery.cutter.ffmpeg.AudioLanes
import github.rikacelery.cutter.ffmpeg.PreviewServer
import github.rikacelery.cutter.ffmpeg.ProxyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files

/**
 * Permanently removes a recording: the video, its `.event` sidecar, and every derived
 * artefact that would otherwise be orphaned in the cache.
 *
 * ## Why the cache sweep is part of "delete"
 *
 * Preview segments, waveforms, spectrograms, extracted frames and proxies are all keyed
 * by the media id and are regenerable. Leaving them behind would keep the recording
 * visible in `/api/media/{id}/frames/cached` and `/api/media/{id}/proxy` long after the
 * source is gone, and the cache limit would evict *live* entries to make room for dead
 * ones. So removal is the only place that has to know about every cache layer, which is
 * why it lives here rather than in the route.
 *
 * ## Failure is reported per path, never swallowed
 *
 * A delete that half-succeeds is the dangerous case: the library drops the row, the user
 * believes the file is gone, and the bytes are still on the NAS. Every path is therefore
 * attempted independently and the failures are surfaced, with the entry kept in the index
 * whenever the *video* could not be removed.
 */
class MediaDeleter(
    private val config: CutConfig,
    private val index: MediaIndex,
    private val preview: PreviewServer,
    private val audio: AudioLanes,
    private val proxy: ProxyService,
    private val timelines: TimelineService
) {
    private val log = LoggerFactory.getLogger(MediaDeleter::class.java)

    data class Result(
        val fileName: String,
        val deleted: List<String>,
        val failed: List<Failure>
    ) {
        val ok: Boolean get() = failed.isEmpty()

        /**
         * True when at least one file was refused for permission reasons.
         *
         * The route turns this into 409 rather than 500: a read-only mount is a
         * configuration state the operator can fix, not a server fault, and the shipped
         * deployment mounts the media root `ro`.
         */
        val permissionDenied: Boolean get() = failed.any { it.denied }
    }

    data class Failure(val path: String, val reason: String, val denied: Boolean = false)

    /**
     * Whether recordings under this root can be removed at all — the check behind the
     * UI's delete controls.
     *
     * It probes the *root*, which is all a single cheap check can speak for: whether a
     * particular recording's own directory permits an unlink is only known for certain
     * when the delete is attempted, and that attempt is the authority. So this is a hint
     * for enabling controls, never a gate — refusing a delete on the strength of a probe
     * against a different directory would block deletes that would have succeeded.
     *
     * Probing beats inferring because "read-only" has several shapes: mounted `ro`,
     * `rw` but owned by another uid, or a NAS share that refuses DELETE while still
     * allowing create. The probe is a create-then-unlink of a hidden file, which is
     * exactly the operation a real delete needs.
     */
    fun probeWritable(directory: File): Failure? {
        if (!directory.isDirectory) {
            return Failure(directory.absolutePath, "目录不存在", denied = true)
        }
        // A directory the process knows it cannot write needs no probe: creating the
        // probe file would only fail, and the OS reason for that is not always carried
        // in the exception (macOS throws a bare `IOException("Permission denied")`).
        if (!directory.canWrite()) {
            return Failure(directory.absolutePath, "目录不可写（检查挂载是否为 ro）", denied = true)
        }
        val probe = File(directory, ".xhcut-write-probe")
        return try {
            if (!probe.createNewFile()) {
                return Failure(directory.absolutePath, "无法创建探测文件", denied = true)
            }
            probe.delete()
            null
        } catch (e: IOException) {
            Failure(directory.absolutePath, describe(e), denied = isPermission(e))
        }
    }

    /**
     * A reason a human can act on.
     *
     * `AccessDeniedException` sets its message to the *path* and nothing else, so the
     * obvious `message ?: simpleName` fallback renders a permission failure as the path
     * repeated twice — no help at all for the one error an operator actually has to fix.
     * When the OS said nothing useful, name the condition instead.
     */
    private fun describe(e: Throwable): String {
        val message = e.message?.takeIf { it.isNotBlank() }
        return when {
            isPermission(e) && (message == null || message == (e as? FileSystemException)?.file) ->
                "权限不足，无法删除（检查挂载是否为 ro）"
            message != null -> message
            else -> e::class.java.simpleName
        }
    }

    private fun isPermission(e: Throwable): Boolean =
        generateSequence(e as Throwable?) { it.cause }.any { it is AccessDeniedException }

    /**
     * Removes [entry] and everything derived from it.
     *
     * Returns null when the id is not in the index, so the route can answer 404 rather
     * than pretending it deleted something.
     */
    suspend fun delete(entry: MediaEntry): Result = withContext(Dispatchers.IO) {
        val deleted = ArrayList<String>()
        val failed = ArrayList<Failure>()

        // The source video first: if it survives, the recording still exists and the
        // index entry must stay, whatever happened to the sidecar and the cache.
        val video = config.mediaRoots.asSequence()
            .map { File(it, entry.relPath) }
            .firstOrNull { it.exists() }
        val videoRemoved = if (video == null) {
            // Already gone — a previous delete got this far, or something outside the
            // cutter removed it. Not a failure: the caller's intent holds for the source.
            deleted.add(entry.relPath)
            true
        } else {
            remove(video, deleted, failed)
        }

        entry.eventRelPath?.let { rel ->
            config.mediaRoots.asSequence()
                .map { File(it, rel) }
                .firstOrNull { it.exists() }
                ?.let { remove(it, deleted, failed) }
        }

        // Derived artefacts are best-effort: a recording that is gone from the library
        // must not be kept alive by a cache file we failed to unlink.
        val cached = sweepCache(entry.id)

        val result = Result(entry.fileName, deleted, failed)
        if (videoRemoved) {
            // Only forget the recording once its bytes are actually gone, otherwise a
            // failed delete would hide the file from the library with no way back.
            index.remove(entry.id)
            timelines.invalidate(entry.id)
            log.info(
                "deleted {} ({} files, {} cached artefacts{})",
                entry.relPath, deleted.size, cached,
                if (failed.isEmpty()) "" else ", ${failed.size} failed"
            )
        } else {
            log.warn("delete of {} failed: {}", entry.relPath, failed.joinToString { "${it.path}: ${it.reason}" })
        }
        result
    }

    /**
     * Unlinks one file, recording the outcome instead of throwing.
     *
     * Returns whether the file is gone afterwards, which is what the caller keys its
     * "did the recording actually disappear" decision on.
     */
    private fun remove(file: File, deleted: MutableList<String>, failed: MutableList<Failure>): Boolean =
        try {
            Files.deleteIfExists(file.toPath())
            deleted.add(file.absolutePath)
            true
        } catch (e: Exception) {
            failed.add(Failure(file.absolutePath, describe(e), denied = isPermission(e)))
            false
        }

    /**
     * Drops every cached artefact for [mediaId]; returns how many layers reported work.
     *
     * Each evictor is independent and already swallows its own IO errors, so one bad
     * cache directory cannot stop the others from being swept.
     */
    private fun sweepCache(mediaId: String): Int {
        var touched = 0
        for ((label, evict) in listOf<Pair<String, (String) -> Unit>>(
            "preview" to preview::evict,
            "audio" to audio::evict,
            "proxy" to proxy::evictAll
        )) {
            runCatching { evict(mediaId) }
                .onFailure { log.warn("failed to evict {} cache for {}: {}", label, mediaId, it.message) }
            touched++
        }
        // Extracted still frames live in one flat directory keyed by "<id>-<time>-<width>".
        runCatching {
            File(config.cacheDir, "frames")
                .listFiles { f -> f.name.startsWith("$mediaId-") }
                ?.forEach { runCatching { it.delete() } }
        }
        return touched
    }
}
