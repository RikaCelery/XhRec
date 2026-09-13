package github.rikacelery.cutter

import github.rikacelery.cutter.config.CutConfig
import github.rikacelery.cutter.config.MissingOptionException
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The three locations are required, and that is a deliberate change from having them
 * default to the paths this tool occupies inside its own container image.
 *
 * A local fallback made a missing `-m`/`-o`/`-c` look like a working start: the process
 * came up, scanned a directory that did not exist or belonged to something else, and
 * served an empty library with no error. These tests keep the requirement from quietly
 * relaxing back into a default.
 */
class CutConfigTest {

    private val all = arrayOf("--media", "/m", "--out", "/o", "--cache", "/c")

    @Test
    fun `all three locations parse`() {
        val config = CutConfig.parse(all)
        assertEquals(listOf(File("/m")), config.mediaRoots)
        assertEquals(File("/o"), config.outputDir)
        assertEquals(File("/c"), config.cacheDir)
    }

    @Test
    fun `a missing media root is refused`() {
        val e = assertFailsWith<MissingOptionException> {
            CutConfig.parse(arrayOf("--out", "/o", "--cache", "/c"))
        }
        assertTrue(e.message!!.contains("media"), "the message must name the missing option: ${e.message}")
    }

    @Test
    fun `a missing output directory is refused`() {
        val e = assertFailsWith<MissingOptionException> {
            CutConfig.parse(arrayOf("--media", "/m", "--cache", "/c"))
        }
        assertTrue(e.message!!.contains("out"), "got: ${e.message}")
    }

    @Test
    fun `a missing cache directory is refused`() {
        val e = assertFailsWith<MissingOptionException> {
            CutConfig.parse(arrayOf("--media", "/m", "--out", "/o"))
        }
        assertTrue(e.message!!.contains("cache"), "got: ${e.message}")
    }

    /** An empty value is a missing value, not a request for the current directory. */
    @Test
    fun `a blank location is refused`() {
        assertFailsWith<MissingOptionException> {
            CutConfig.parse(arrayOf("--media", " ", "--out", "/o", "--cache", "/c"))
        }
        assertFailsWith<MissingOptionException> {
            CutConfig.parse(arrayOf("--media", "/m", "--out", "  ", "--cache", "/c"))
        }
    }

    @Test
    fun `several media roots may be given`() {
        val config = CutConfig.parse(arrayOf("--media", "/a, /b", "--out", "/o", "--cache", "/c"))
        assertEquals(listOf(File("/a"), File("/b")), config.mediaRoots)
    }

    /** Non-path options keep their defaults; only the locations are mandatory. */
    @Test
    fun `other options still default`() {
        val config = CutConfig.parse(all)
        assertEquals(8092, config.port)
        assertEquals("ffmpeg", config.ffmpeg)
        assertTrue(config.skipDirNames.isEmpty(), "nothing is skipped unless asked")
    }
}
