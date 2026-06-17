package github.rikacelery.v3.components

import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.data.SystemConfig
import github.rikacelery.v3.events.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigComponentTest {

    @TempDir
    lateinit var tempDir: Path

    private fun TestScope.createComponents(): Triple<EventBus, RequestBus, ConfigComponent> {
        val configPath = tempDir.resolve("xhrec.json").toFile()
        val config = SystemConfig(
            outputDir = tempDir.toFile(), tmpDir = tempDir.toFile(),
            port = 8080, proxy = null,
            decryptKeys = mapOf("key1" to "secret1", "key2" to "secret2"),
            streamAuthKey = "auth-secret", authToken = "tok",
            platformHost = "ex.com", configPath = configPath.absolutePath
        )
        val bus = EventBus()
        val comp = ConfigComponent(config, bus, this)
        // RequestBus must use backgroundScope so its subscriber coroutine
        // gets cancelled at test end, avoiding UncompletedCoroutinesError
        val rb = RequestBus(bus, backgroundScope)
        return Triple(bus, rb, comp)
    }

    @Test
    fun `GetDecryptKey found returns key value`() = runTest(UnconfinedTestDispatcher()) {
        val (_, rb, comp) = createComponents()
        comp.start()

        val result = rb.request<ConfigResponse>(GetDecryptKey("key1"))
        assertEquals("secret1", result.value)
        comp.stop()
    }

    @Test
    fun `GetDecryptKey not found returns ConfigResponse with null`() = runTest(UnconfinedTestDispatcher()) {
        val (_, rb, comp) = createComponents()
        comp.start()

        val result = rb.request<ConfigResponse>(GetDecryptKey("nonexistent"))
        assertNull(result.value)
        comp.stop()
    }

    @Test
    fun `MatchDecryptKeys finds first matching key`() = runTest(UnconfinedTestDispatcher()) {
        val (_, rb, comp) = createComponents()
        comp.start()

        val result = rb.request<DecryptKeyMatch>(MatchDecryptKeys(listOf("unknown", "key2", "key1")))
        assertEquals("key2", result.keyName)
        assertEquals("secret2", result.decryptKey)
        comp.stop()
    }

    @Test
    fun `MatchDecryptKeys no match returns empty DecryptKeyMatch`() = runTest(UnconfinedTestDispatcher()) {
        val (_, rb, comp) = createComponents()
        comp.start()

        val result = rb.request<DecryptKeyMatch>(MatchDecryptKeys(listOf("a", "b")))
        assertEquals("", result.keyName)
        assertEquals("", result.decryptKey)
        comp.stop()
    }

    @Test
    fun `ToggleMask flips value`() = runTest(UnconfinedTestDispatcher()) {
        val (_, rb, comp) = createComponents()
        comp.start()

        val initial = rb.request<ConfigResponse>(GetMaskStatus)
        assertEquals(true, initial.value)

        val afterToggle = rb.request<ConfigResponse>(ToggleMask)
        assertEquals(false, afterToggle.value)
        comp.stop()
    }

    @Test
    fun `saveConfig then loadConfig round-trips`() = runTest(UnconfinedTestDispatcher()) {
        val configPath = tempDir.resolve("xhrec2.json").toFile()
        val config = SystemConfig(
            outputDir = tempDir.toFile(), tmpDir = tempDir.toFile(),
            port = 8080, proxy = null,
            decryptKeys = mapOf("k" to "v"), streamAuthKey = "auth",
            authToken = "tok", platformHost = "ex.com",
            configPath = configPath.absolutePath
        )
        val bus = EventBus()
        val comp1 = ConfigComponent(config, bus, this)
        comp1.start()
        val rb1 = RequestBus(bus, backgroundScope)
        rb1.request<ConfigResponse>(ToggleMask)

        // give async IO save time to complete
        delay(300)

        val comp2 = ConfigComponent(config, bus, this)
        comp2.start()
        val rb2 = RequestBus(bus, backgroundScope)
        val mask = rb2.request<ConfigResponse>(GetMaskStatus)
        assertEquals(false, mask.value)

        comp1.stop()
        comp2.stop()
    }

    @Test
    fun `saveConfig in PersistConfig does not block command handling`() = runTest(UnconfinedTestDispatcher()) {
        val (bus, rb, comp) = createComponents()
        comp.start()

        bus.publish(PersistConfig)
        // if collector were blocked by saveConfig, this would time out
        val result = rb.request<ConfigResponse>(GetDecryptKey("key1"))
        assertEquals("secret1", result.value)
        comp.stop()
    }
}
