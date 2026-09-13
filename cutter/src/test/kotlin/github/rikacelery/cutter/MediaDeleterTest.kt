package github.rikacelery.cutter

import github.rikacelery.cutter.config.CutConfig
import github.rikacelery.cutter.events.TimelineService
import github.rikacelery.cutter.ffmpeg.AudioLanes
import github.rikacelery.cutter.ffmpeg.PreviewServer
import github.rikacelery.cutter.ffmpeg.ProxyService
import github.rikacelery.cutter.media.MediaDeleter
import github.rikacelery.cutter.media.MediaIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deletion is the one irreversible operation in the tool, so these tests are written
 * against a real directory tree rather than mocks: what matters is which paths are gone
 * afterwards, and whether the library forgot a recording whose bytes are still there.
 *
 * The two properties that would be dangerous to get wrong:
 *
 *  - a recording must not disappear from the index while its video survives, or a failed
 *    delete silently hides the file with no way back;
 *  - nothing derived from it (sidecar, preview, waveform, frames, proxy) may be left
 *    behind, or the dead recording stays visible through those endpoints and the cache
 *    limit starts evicting live entries to make room for it.
 */
class MediaDeleterTest {

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().apply { deleteOnExit() }

    private fun configFor(root: File, cache: File) = CutConfig(
        port = 0,
        mediaRoots = listOf(root),
        outputDir = File(cache, "out"),
        cacheDir = cache,
        zone = ZoneId.of("Asia/Shanghai"),
        ffmpeg = "ffmpeg",
        ffprobe = "ffprobe",
        previewSegmentSeconds = 4,
        previewConcurrency = 2,
        cacheLimitBytes = 1024L * 1024 * 1024,
        minFreeBytes = 0
    )

    private fun touch(file: File, bytes: Int = 16) {
        file.parentFile.mkdirs()
        file.writeBytes(ByteArray(bytes))
    }

    private class Rig(
        val root: File,
        val cache: File,
        val index: MediaIndex,
        val deleter: MediaDeleter,
        val scope: CoroutineScope
    )

    /** Builds a real index + deleter over throwaway directories, with one indexed recording. */
    private fun rig(): Rig {
        val root = tempDir("xhcut-del-media")
        val cache = tempDir("xhcut-del-cache")
        val config = configFor(root, cache)
        val index = MediaIndex(config)
        val timelines = TimelineService(config, index)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val previews = PreviewServer(config, index)
        val audio = AudioLanes(config)
        val proxies = ProxyService(config, scope)
        val deleter = MediaDeleter(config, index, previews, audio, proxies, timelines)

        touch(File(root, "room/room-2026_05_11_12_03_42-09h11m44s.fixed.mp4"), 4096)
        touch(File(root, "room/room-2026_05_11_12_03_42-09h11m44s.fixed.event"), 128)
        runBlocking { index.scan() }
        return Rig(root, cache, index, deleter, scope)
    }

    private fun Rig.entry() = index.entries.singleOrNull() ?: error("expected exactly one indexed recording")

    /** Creates one file in each cache layer the deleter is responsible for sweeping. */
    private fun seedCaches(rig: Rig, id: String) {
        touch(File(rig.cache, "preview/$id/low-4-1/0.ts"))
        touch(File(rig.cache, "audio/$id/overview.png"))
        touch(File(rig.cache, "proxy/$id-360p2fps.mp4"))
        touch(File(rig.cache, "frames/$id-12.500-800.jpg"))
        // A second recording's artefacts must survive: the sweep is keyed by media id.
        touch(File(rig.cache, "frames/other-id-3.000-800.jpg"))
        touch(File(rig.cache, "proxy/other-id-360p2fps.mp4"))
    }

    private fun assertCachesSwept(rig: Rig, id: String) {
        assertFalse(File(rig.cache, "preview/$id").exists(), "preview segments must be swept")
        assertFalse(File(rig.cache, "audio/$id").exists(), "waveform/spectrogram must be swept")
        assertFalse(File(rig.cache, "proxy/$id-360p2fps.mp4").exists(), "proxy must be swept")
        assertFalse(File(rig.cache, "frames/$id-12.500-800.jpg").exists(), "still frames must be swept")
        assertTrue(
            File(rig.cache, "frames/other-id-3.000-800.jpg").isFile,
            "another recording's frames must not be touched"
        )
        assertTrue(
            File(rig.cache, "proxy/other-id-360p2fps.mp4").isFile,
            "another recording's proxy must not be touched"
        )
    }

    @Test
    fun `delete removes the video, the sidecar, and every cache layer`() = runBlocking {
        val rig = rig()
        val entry = rig.entry()
        seedCaches(rig, entry.id)

        val result = rig.deleter.delete(entry)

        assertTrue(result.ok, "delete reported failures: ${result.failed}")
        assertFalse(File(rig.root, entry.relPath).exists(), "video must be gone")
        assertFalse(
            File(rig.root, entry.eventRelPath!!).exists(),
            "the .event sidecar must go with the video, not be orphaned"
        )
        assertCachesSwept(rig, entry.id)
        assertNull(rig.index.find(entry.id), "a deleted recording must leave the index")
        rig.scope.cancel()
    }

    @Test
    fun `delete reports the paths it removed`() = runBlocking {
        val rig = rig()
        val entry = rig.entry()
        seedCaches(rig, entry.id)

        val result = rig.deleter.delete(entry)

        assertEquals(entry.fileName, result.fileName)
        assertTrue(
            result.deleted.any { it.endsWith(entry.relPath) },
            "the video path must be reported: ${result.deleted}"
        )
        assertTrue(
            result.deleted.any { it.endsWith(".event") },
            "the sidecar path must be reported: ${result.deleted}"
        )
        rig.scope.cancel()
    }

    /**
     * A read-only media root is the shipped configuration, so this is the path an operator
     * hits first. The recording must stay in the index — pretending it was deleted would
     * hide a file that is still on the NAS.
     */
    @Test
    fun `a failed delete keeps the recording in the index`() = runBlocking {
        val rig = rig()
        val entry = rig.entry()
        val dir = File(rig.root, "room")
        // Unlinking needs write permission on the *directory*, not the file.
        if (!dir.setWritable(false)) {
            rig.scope.cancel()
            return@runBlocking // running as root: the OS will not deny us, so nothing to assert
        }
        try {
            val result = rig.deleter.delete(entry)
            assertFalse(result.ok, "a read-only root must be reported as a failure")
            assertTrue(result.failed.isNotEmpty())
            // The route keys its HTTP status off this: permission trouble is a
            // configuration state (409), anything else is a server fault (500).
            assertTrue(result.permissionDenied, "a refused unlink must be flagged as permission denial")
            // The reason must be actionable. `AccessDeniedException` carries no message, and
            // the old fallback printed the path twice, which tells an operator nothing.
            val reason = result.failed.first().reason
            assertTrue(
                reason.isNotBlank() && reason != result.failed.first().path,
                "the failure reason must describe the cause, got '$reason'"
            )
            assertNotNull(rig.index.find(entry.id), "the index must keep a recording whose bytes survive")
            assertTrue(File(rig.root, entry.relPath).isFile)
        } finally {
            dir.setWritable(true)
            rig.scope.cancel()
        }
    }

    /** An already-missing file is a satisfied intent, not an error to report. */
    @Test
    fun `deleting an already-missing file still succeeds`() = runBlocking {
        val rig = rig()
        val entry = rig.entry()
        File(rig.root, entry.relPath).delete()

        val result = rig.deleter.delete(entry)

        assertTrue(result.ok, "a partially-removed recording should still be cleanable: ${result.failed}")
        assertNull(rig.index.find(entry.id))
        rig.scope.cancel()
    }

    @Test
    fun `the writability probe accepts a writable root`() {
        val rig = rig()
        assertNull(rig.deleter.probeWritable(rig.root), "a temp dir is writable")
        assertFalse(File(rig.root, ".xhcut-write-probe").exists(), "the probe must clean up after itself")
        rig.scope.cancel()
    }

    @Test
    fun `the writability probe rejects a root that cannot be written`() {
        val rig = rig()
        if (!rig.root.setWritable(false)) {
            rig.scope.cancel()
            return // running as root
        }
        try {
            val blocked = rig.deleter.probeWritable(rig.root)
            assertNotNull(blocked, "an unwritable root must be reported")
            assertTrue(blocked.denied, "a read-only root is a permission problem, not a mystery")
        } finally {
            rig.root.setWritable(true)
            rig.scope.cancel()
        }
    }

    @Test
    fun `the index removal is persisted so a restart does not resurrect the row`() = runBlocking {
        val rig = rig()
        val entry = rig.entry()

        rig.deleter.delete(entry)

        // The persisted cache is what a restart loads before its first scan.
        val reloaded = MediaIndex(configFor(rig.root, rig.cache))
        assertTrue(reloaded.loadCache(), "index cache should exist")
        assertNull(reloaded.find(entry.id), "the deleted row must not come back from disk")
        rig.scope.cancel()
    }
}
