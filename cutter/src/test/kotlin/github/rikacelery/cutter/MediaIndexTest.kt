package github.rikacelery.cutter

import github.rikacelery.cutter.config.CutConfig
import github.rikacelery.cutter.media.MediaIndex
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end index behaviour over a throwaway directory tree.
 *
 * Pairing rules matter more than they look: the production corpus holds 11032 event
 * files against 34085 recordings, and the naming drifted across processor
 * generations, so several sibling spellings have to resolve to the same recording.
 */
class MediaIndexTest {

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

    private fun indexOf(root: File, cache: File): MediaIndex =
        MediaIndex(configFor(root, cache)).also { runBlocking { it.scan() } }

    @Test
    fun `pairs every event naming variant`() {
        val root = tempDir("idx-pair")
        val cache = tempDir("idx-cache")

        // 1. the plain `<mp4>.event` sibling
        touch(File(root, "roomA/roomA-2026-01-02-030405-00h00m12s.mp4"))
        touch(File(root, "roomA/roomA-2026-01-02-030405-00h00m12s.mp4.event"))

        // 2. the `<stem>.event` form
        touch(File(root, "roomB/roomB-2026-01-02-030405-00h00m12s.mp4"))
        touch(File(root, "roomB/roomB-2026-01-02-030405-00h00m12s.event"))

        // 3. the `.fixed` infix, event named after the unfixed stem
        touch(File(root, "roomC/roomC-2026-01-02-030405-00h00m12s.fixed.mp4"))
        touch(File(root, "roomC/roomC-2026-01-02-030405-00h00m12s.event"))

        // 4. the `.fixed` infix on both sides
        touch(File(root, "roomD/roomD-2026-01-02-030405-00h00m12s.fixed.mp4"))
        touch(File(root, "roomD/roomD-2026-01-02-030405-00h00m12s.fixed.event"))

        // 5. a recording with no event at all
        touch(File(root, "roomE/roomE-2026-01-02-030405-00h00m12s.mp4"))

        val entries = indexOf(root, cache).entries.associateBy { it.room }

        assertEquals(5, entries.size)
        for (room in listOf("roomA", "roomB", "roomC", "roomD")) {
            val entry = assertNotNull(entries[room], "missing $room")
            assertTrue(entry.hasEvents, "$room should have an event sibling")
            assertNotNull(entry.eventRelPath)
        }
        assertNull(entries["roomE"]!!.eventRelPath)
    }

    @Test
    fun `event path is relative and resolves back to the file`() {
        val root = tempDir("idx-rel")
        val cache = tempDir("idx-cache")
        touch(File(root, "deep/nested/roomZ/roomZ-2026-01-02-030405-00h00m12s.mp4"))
        touch(File(root, "deep/nested/roomZ/roomZ-2026-01-02-030405-00h00m12s.mp4.event"))

        val index = indexOf(root, cache)
        val entry = index.entries.single()
        assertEquals("deep/nested/roomZ/roomZ-2026-01-02-030405-00h00m12s.mp4", entry.relPath)
        assertEquals(
            "deep/nested/roomZ/roomZ-2026-01-02-030405-00h00m12s.mp4.event",
            entry.eventRelPath
        )
        assertNotNull(index.resolve(entry))
        assertNotNull(index.resolveEvent(entry))
    }

    /**
     * Hidden entries are scanned.
     *
     * They used to be skipped, on the theory that a dot-prefixed file is an in-flight or
     * quarantined download. In this corpus that is wrong: recordings are deliberately
     * filed under hidden folders (XhRec's own `.unwanted` archive is one), and skipping
     * them made the library look like the scanner had missed files, with nothing in the
     * UI to explain the absence.
     */
    @Test
    fun `hidden directories and files are indexed`() {
        val root = tempDir("idx-hidden")
        val cache = tempDir("idx-cache")
        touch(File(root, "roomA/roomA-2026-01-02-030405-00h00m12s.mp4"))
        touch(File(root, ".unwanted/discard-2026-01-02-030405-00h00m12s.mp4"))
        touch(File(root, "roomA/.hidden-2026-01-02-030405-00h00m12s.mp4"))

        val entries = indexOf(root, cache).entries
        assertEquals(
            setOf(
                "roomA-2026-01-02-030405-00h00m12s.mp4",
                "discard-2026-01-02-030405-00h00m12s.mp4",
                ".hidden-2026-01-02-030405-00h00m12s.mp4"
            ),
            entries.map { it.fileName }.toSet(),
            "the hidden folder and the hidden file must both be present"
        )
    }

    /**
     * The escape hatch: hidden trees are scanned by default, but a named directory can be
     * excluded without changing the scanner. The recording host uses it for `.unwanted`.
     */
    @Test
    fun `a configured skip directory is not descended into`() = runBlocking {
        val root = tempDir("idx-skip")
        val cache = tempDir("idx-cache")
        touch(File(root, "roomA/roomA-2026-01-02-030405-00h00m12s.mp4"))
        touch(File(root, ".unwanted/discard-2026-01-02-030405-00h00m12s.mp4"))

        val index = MediaIndex(configFor(root, cache).copy(skipDirNames = setOf(".unwanted")))
        index.scan()

        assertEquals(
            listOf("roomA-2026-01-02-030405-00h00m12s.mp4"),
            index.entries.map { it.fileName },
            "the skipped tree must contribute nothing"
        )
    }

    /** A hidden folder's sidecar has to pair up like any other. */
    @Test
    fun `a recording inside a hidden folder keeps its event sidecar`() {
        val root = tempDir("idx-hidden-event")
        val cache = tempDir("idx-cache")
        touch(File(root, ".unwanted/discard-2026-01-02-030405-00h00m12s.mp4"))
        touch(File(root, ".unwanted/discard-2026-01-02-030405-00h00m12s.mp4.event"))

        val entry = indexOf(root, cache).entries.single()
        assertTrue(entry.hasEvents, "the sidecar must be paired, not dropped with the dot-prefix")
        assertTrue(entry.eventRelPath!!.startsWith(".unwanted"), "got ${entry.eventRelPath}")
    }

    @Test
    fun `records size and parses start time from the name`() {
        val root = tempDir("idx-meta")
        val cache = tempDir("idx-cache")
        touch(File(root, "roomA/roomA-2026-01-02-030405-00h00m12s.mp4"), bytes = 1234)

        val entry = indexOf(root, cache).entries.single()
        assertEquals(1234L, entry.sizeBytes)
        assertEquals(12L, entry.nameDurationSeconds)
        // 2026-01-02 03:04:05 +08:00 == 2026-01-01 19:04:05 UTC
        assertEquals(1767294245L, entry.startEpochSeconds)
        assertEquals("roomA", entry.room)
    }

    @Test
    fun `cache round trips and survives a reload`() {
        val root = tempDir("idx-cache-rt")
        val cache = tempDir("idx-cache")
        touch(File(root, "roomA/roomA-2026-01-02-030405-00h00m12s.mp4"))
        touch(File(root, "roomA/roomA-2026-01-02-030405-00h00m12s.mp4.event"))

        val cfg = configFor(root, cache)
        val first = MediaIndex(cfg)
        runBlocking { first.scan() }
        val generation = first.generation
        assertTrue(cfg.indexFile.isFile, "index cache should be written to ${cfg.indexFile}")

        val reloaded = MediaIndex(cfg)
        assertTrue(runBlocking { reloaded.loadCache() })
        assertEquals(generation, reloaded.generation)
        assertEquals(1, reloaded.entries.size)
        assertTrue(reloaded.entries.single().hasEvents)
    }

    @Test
    fun `a recording whose stat fails is still indexed`() {
        val root = tempDir("idx-nostat")
        val cache = tempDir("idx-cache")
        touch(File(root, "roomA/roomA-2026-01-02-030405-00h00m12s.mp4"))
        val entries = indexOf(root, cache).entries
        assertEquals(1, entries.size)
        assertEquals("roomA", entries.single().room)
    }
}
