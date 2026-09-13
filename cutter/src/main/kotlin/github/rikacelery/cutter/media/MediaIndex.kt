package github.rikacelery.cutter.media

import github.rikacelery.cutter.config.CutConfig
import github.rikacelery.cutter.ffmpeg.Proc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/**
 * Recursive index of every recording under [CutConfig.mediaRoots].
 *
 * ## Why this walk is shaped the way it is
 *
 * The production tree lives on a CIFS mount where a `stat` costs roughly 0.3 ms and
 * the tree holds ~45k entries, so *the number of stat calls is the whole cost*.
 * Measured on the recording host:
 *
 *   - pure directory walk, no stats ......... 11.7 s / 45144 entries
 *   - + stat every `.mp4` ................... 10.6 s / 34085 stats
 *   - + stat every entry .................... 19.2 s / 45144 stats
 *
 * An earlier version of this class called `File.isDirectory`, `File.length`,
 * `File.lastModified` and up to four `File.isFile` probes per recording — six or
 * seven stats each, well over 200k syscalls — and took more than 150 s. The
 * current shape pins the cost at exactly one attribute read per recording:
 *
 *   1. one `listFiles()` per directory (readdir only, no stats),
 *   2. `.event` siblings are matched by *name* against that listing, so pairing
 *      costs zero stats,
 *   3. a single `Files.readAttributes` per `.mp4` yields size and mtime together.
 *
 * The result is cached to disk, so a restart serves the library instantly.
 */
class MediaIndex(private val config: CutConfig) {

    private val log = LoggerFactory.getLogger(MediaIndex::class.java)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val snapshot = AtomicReference<List<MediaEntry>>(emptyList())

    /** Monotonic counter so the UI can tell a finished rescan from a stale list. */
    @Volatile
    var generation: Long = 0
        private set

    @Volatile
    var lastScanAt: Long = 0
        private set

    @Volatile
    var scanning: Boolean = false
        private set

    @Volatile
    var lastScanMillis: Long = 0
        private set

    val entries: List<MediaEntry> get() = snapshot.get()

    fun find(id: String): MediaEntry? = snapshot.get().firstOrNull { it.id == id }

    /**
     * Drop [id] from the index after its files are gone.
     *
     * The library list is client-side, so the entry has to disappear server-side too —
     * otherwise the next `/api/library` returns a row that opens into a 404. The
     * generation is bumped for the same reason a scan bumps it: it is how a client tells
     * "this list is stale" from "this list is current".
     *
     * A concurrent [scan] may re-add the entry if it still finds the file; that is the
     * wanted behaviour, since a failed delete must not hide the recording.
     */
    suspend fun remove(id: String): Boolean {
        val current = snapshot.get()
        val next = current.filterNot { it.id == id }
        if (next.size == current.size) return false
        snapshot.set(next)
        generation += 1
        persist(next)
        return true
    }

    /** Absolute path of a recording, resolved against the roots. */
    fun resolve(entry: MediaEntry): File? =
        config.mediaRoots.asSequence().map { File(it, entry.relPath) }.firstOrNull { it.isFile }

    fun resolveEvent(entry: MediaEntry): File? {
        val rel = entry.eventRelPath ?: return null
        return config.mediaRoots.asSequence().map { File(it, rel) }.firstOrNull { it.isFile }
    }

    suspend fun loadCache(): Boolean = withContext(Dispatchers.IO) {
        val file = config.indexFile
        if (!file.isFile) return@withContext false
        runCatching {
            val cached = json.decodeFromString(IndexFile.serializer(), file.readText())
            snapshot.set(cached.entries)
            generation = cached.generation
            lastScanAt = cached.scannedAt
            log.info("loaded index cache: {} entries (generation {})", cached.entries.size, cached.generation)
            true
        }.getOrElse {
            log.warn("ignoring unreadable index cache {}: {}", file.absolutePath, it.message)
            false
        }
    }

    /**
     * Walk every media root. Read errors on individual directories are logged and
     * skipped — one unreadable folder must not fail the whole library.
     */
    suspend fun scan(): List<MediaEntry> = withContext(Dispatchers.IO) {
        scanning = true
        try {
            val started = System.currentTimeMillis()
            val found = ArrayList<MediaEntry>(40_000)
            for (root in config.mediaRoots) {
                if (!root.isDirectory) {
                    log.warn("media root is not a directory, skipping: {}", root.absolutePath)
                    continue
                }
                walk(root, found)
            }
            val elapsed = System.currentTimeMillis() - started
            lastScanMillis = elapsed
            log.info("scan complete: {} recordings in {} ms", found.size, elapsed)

            // Carry derived heat across a rescan.
            //
            // A rescan rebuilds every entry from scratch, so without this the freshly
            // scanned entries have `heat = null` and the library silently loses its
            // rankings until something recomputes them — which nothing did, because
            // only startup ran the enrichment pass.
            val previousHeat = snapshot.get().mapNotNull { entry -> entry.heat?.let { entry.id to it } }.toMap()
            if (previousHeat.isNotEmpty()) {
                for (i in found.indices) {
                    val heat = previousHeat[found[i].id] ?: continue
                    found[i] = found[i].copy(heat = heat)
                }
            }

            snapshot.set(found)
            generation += 1
            lastScanAt = System.currentTimeMillis()
            persist(found)
            found
        } finally {
            scanning = false
        }
    }

    private fun walk(root: File, into: MutableList<MediaEntry>) {
        val rootPath = root.absolutePath
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = runCatching { dir.listFiles() }.getOrNull()
            if (children == null) {
                log.warn("skipping unreadable directory: {}", dir.absolutePath)
                continue
            }

            // One readdir for the whole directory; classify purely by name so that
            // pairing a recording with its `.event` sibling costs no syscalls.
            val eventNames = HashSet<String>()
            val videos = ArrayList<File>()
            val subdirs = ArrayList<File>()
            for (child in children) {
                val name = child.name
                // An explicit opt-out, checked before anything else so a skipped tree
                // costs nothing. Empty unless the operator configured it.
                if (name in config.skipDirNames) continue
                when {
                    // Hidden entries are scanned too, including directories such as the
                    // corpus's `.unwanted` archive. They used to be skipped on the theory
                    // that a dot-prefixed `.mp4` is an in-flight download, but that also
                    // hid every recording deliberately filed under a hidden folder, which
                    // reads as "the scanner missed my files" with nothing to point at.
                    name.endsWith(".event") -> eventNames.add(name)
                    name.endsWith(".mp4") -> videos.add(child)
                    // Everything else is only interesting if it is a directory to
                    // descend into, so the syscall-costing test comes last.
                    isDirectory(child) -> subdirs.add(child)
                }
            }
            for (sub in subdirs) stack.addLast(sub)

            for (video in videos) {
                runCatching { toEntry(rootPath, video, eventNames) }
                    .onSuccess { into.add(it) }
                    .onFailure { log.warn("skipping {}: {}", video.absolutePath, it.message) }
            }
        }
    }

    private fun isDirectory(file: File): Boolean =
        runCatching { Files.isDirectory(file.toPath()) }.getOrDefault(false)

    private fun toEntry(rootPath: String, video: File, eventNames: Set<String>): MediaEntry {
        val rel = video.absolutePath.removePrefix(rootPath).trimStart(File.separatorChar)
        val stamp = RecordingName.parse(video.name)

        // The single stat for this recording. If it fails we still index the file
        // with an unknown size rather than dropping it from the library.
        val attrs = runCatching {
            Files.readAttributes(video.toPath(), BasicFileAttributes::class.java)
        }.getOrNull()

        val eventRel = matchEvent(video.name, eventNames)?.let { eventName ->
            val parentRel = rel.substringBeforeLast(File.separatorChar, "")
            if (parentRel.isEmpty()) eventName else "$parentRel${File.separatorChar}$eventName"
        }

        return MediaEntry(
            id = stableId(rel),
            relPath = rel,
            fileName = video.name,
            room = RecordingName.room(video.name),
            sizeBytes = attrs?.size() ?: 0L,
            modifiedAt = attrs?.lastModifiedTime()?.toMillis() ?: 0L,
            startEpochSeconds = stamp.startEpochSeconds(config.zone),
            nameDurationSeconds = stamp.durationSeconds,
            eventRelPath = eventRel
        )
    }

    /**
     * Match a recording to a sibling event file by name. Names drifted across
     * processor generations (`X.mp4.event`, `X.event`, `X.fixed.event`), so the
     * candidates are tried against the already-read directory listing.
     */
    private fun matchEvent(videoName: String, eventNames: Set<String>): String? {
        val stem = videoName.removeSuffix(".mp4")
        if ("$videoName.event" in eventNames) return "$videoName.event"
        if ("$stem.event" in eventNames) return "$stem.event"
        if (stem.endsWith(".fixed")) {
            val base = stem.removeSuffix(".fixed")
            if ("$base.event" in eventNames) return "$base.event"
            if ("$base.mp4.event" in eventNames) return "$base.mp4.event"
        }
        return null
    }

    private suspend fun persist(entries: List<MediaEntry>) = withContext(Dispatchers.IO) {
        runCatching {
            Proc.ensureDir(config.cacheDir)
            val payload = IndexFile(generation, lastScanAt, entries)
            val tmp = File(config.cacheDir, "${config.indexFileName}.tmp")
            tmp.writeText(json.encodeToString(IndexFile.serializer(), payload))
            if (!tmp.renameTo(config.indexFile)) {
                config.indexFile.delete()
                tmp.renameTo(config.indexFile)
            }
        }.onFailure { log.warn("could not persist index: {}", it.message) }
    }

    /** Replace stored heat for a batch of ids (background enrichment). */
    suspend fun applyHeat(updates: Map<String, Heat>) {
        if (updates.isEmpty()) return
        val current = snapshot.get()
        var changed = false
        val next = current.map { entry ->
            val heat = updates[entry.id]
            if (heat != null) {
                changed = true
                entry.copy(heat = heat)
            } else {
                entry
            }
        }
        if (changed) {
            snapshot.set(next)
            persist(next)
        }
    }

    @Serializable
    private data class IndexFile(
        val generation: Long,
        val scannedAt: Long,
        val entries: List<MediaEntry>
    )

    companion object {
        /** Path-stable, URL-safe id. */
        fun stableId(relPath: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(relPath.toByteArray())
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
