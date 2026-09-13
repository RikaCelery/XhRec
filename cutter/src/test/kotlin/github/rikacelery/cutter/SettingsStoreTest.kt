package github.rikacelery.cutter

import github.rikacelery.cutter.config.SettingsStore
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings have to survive a restart, because the failure mode of losing them is silent:
 * the exporter would stop cleaning up after itself and the NAS would fill up with sources
 * the operator believed were already gone.
 */
class SettingsStoreTest {

    private fun tempFile(): File =
        Files.createTempDirectory("xhcut-settings").toFile().apply { deleteOnExit() }
            .resolve("settings.json")

    @Test
    fun `defaults are off`() {
        val store = SettingsStore(tempFile())
        assertFalse(store.value.deleteSourceAfterExport, "deleting sources must be opt-in")
    }

    @Test
    fun `a saved setting survives a reload`() = runBlocking {
        val file = tempFile()
        SettingsStore(file).update { it.copy(deleteSourceAfterExport = true) }

        val reloaded = SettingsStore(file)
        reloaded.load()
        assertTrue(reloaded.value.deleteSourceAfterExport, "the setting must persist across restarts")
        assertNull(reloaded.lastError)
    }

    @Test
    fun `turning it back off persists too`() = runBlocking {
        val file = tempFile()
        val store = SettingsStore(file)
        store.update { it.copy(deleteSourceAfterExport = true) }
        store.update { it.copy(deleteSourceAfterExport = false) }

        val reloaded = SettingsStore(file)
        reloaded.load()
        assertFalse(reloaded.value.deleteSourceAfterExport)
    }

    /** A corrupt file must fall back to the safe default rather than failing to start. */
    @Test
    fun `an unreadable file falls back to defaults and reports it`() = runBlocking {
        val file = tempFile()
        file.parentFile.mkdirs()
        file.writeText("{ this is not json")

        val store = SettingsStore(file)
        store.load()

        assertFalse(store.value.deleteSourceAfterExport)
        assertNotNull(store.lastError, "the UI needs to know the setting could not be read")
    }

    @Test
    fun `an unknown field from a newer version does not break loading`() = runBlocking {
        val file = tempFile()
        file.parentFile.mkdirs()
        file.writeText("""{"deleteSourceAfterExport":true,"somethingNew":42}""")

        val store = SettingsStore(file)
        store.load()

        assertTrue(store.value.deleteSourceAfterExport)
        assertNull(store.lastError)
    }

    @Test
    fun `values round-trip through the file`() = runBlocking {
        val file = tempFile()
        SettingsStore(file).update { it.copy(deleteSourceAfterExport = true) }
        val text = file.readText()
        assertTrue(text.contains("deleteSourceAfterExport"), "expected readable JSON, got: $text")
        assertEquals(
            true,
            SettingsStore(file).also { it.load() }.value.deleteSourceAfterExport
        )
    }
}
