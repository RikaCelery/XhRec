package github.rikacelery.v3.components

import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.data.HostsConfig
import github.rikacelery.v3.data.SystemConfig
import github.rikacelery.v3.events.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
            streamAuthKey = "auth-secret", 
            hosts = HostsConfig(platformHosts = listOf("ex.com"), webSocketHosts = listOf("ws.ex.com")),
            configPath = configPath.absolutePath
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
             hosts = HostsConfig(platformHosts = listOf("ex.com")),
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
    fun `hosts are loaded from config file and preserved on save`() = runTest(UnconfinedTestDispatcher()) {
        val configPath = tempDir.resolve("xhrec-hosts.json").toFile()
        configPath.writeText("{\"platformHosts\":[\"mirror.example.com\"],\"webSocketHosts\":[\"ws.mirror.example.com\"],\"hlsHosts\":[\"cdn.mirror.example.com\"],\"hlsMasterHost\":\"master.mirror.example.com\"}")
        val config = SystemConfig(
            outputDir = tempDir.toFile(), tmpDir = tempDir.toFile(),
            port = 8080, proxy = null,
            decryptKeys = mapOf("k" to "v"), streamAuthKey = "auth",
             hosts = HostsConfig(platformHosts = listOf("default.example.com")),
            configPath = configPath.absolutePath
        )
        val bus = EventBus()
        val comp = ConfigComponent(config, bus, this)
        comp.start()

        // give async IO save time to complete
        delay(300)

        val saved = Json.parseToJsonElement(configPath.readText()).jsonObject
        assertEquals("mirror.example.com", saved["platformHosts"]?.jsonArray?.first()?.jsonPrimitive?.content)
        assertEquals("ws.mirror.example.com", saved["webSocketHosts"]?.jsonArray?.first()?.jsonPrimitive?.content)
        assertEquals("cdn.mirror.example.com", saved["hlsHosts"]?.jsonArray?.first()?.jsonPrimitive?.content)
        assertEquals("master.mirror.example.com", saved["hlsMasterHost"]?.jsonPrimitive?.content)
        comp.stop()
    }

    @Test
    fun `SetHostsConfig updates runtime and persists`() = runTest(UnconfinedTestDispatcher()) {
        val configPath = tempDir.resolve("xhrec-set.json").toFile()
        val config = SystemConfig(
            outputDir = tempDir.toFile(), tmpDir = tempDir.toFile(),
            port = 8080, proxy = null,
            decryptKeys = mapOf("k" to "v"), streamAuthKey = "auth",
             hosts = HostsConfig(platformHosts = listOf("old.example.com")),
            configPath = configPath.absolutePath
        )
        val bus = EventBus()
        val comp = ConfigComponent(config, bus, this)
        comp.start()
        val rb = RequestBus(bus, backgroundScope)

        rb.request<OkResponse>(
            SetHostsConfig(
                HostsConfig(
                    platformHosts = listOf("new1.example.com", "new2.example.com"),
                    webSocketHosts = listOf("ws.new.example.com"),
                    hlsHosts = listOf("cdn.new.example.com", "cdn2.new.example.com")
                )
            )
        )

        val cfg = rb.request<HostsConfigResponse>(GetHostsConfig).hosts
        assertEquals(listOf("new1.example.com", "new2.example.com"), cfg.platformHosts)
        assertEquals(listOf("cdn.new.example.com", "cdn2.new.example.com"), cfg.hlsHosts)

        delay(300)
        val saved = Json.parseToJsonElement(configPath.readText()).jsonObject
        assertEquals("new1.example.com", saved["platformHosts"]?.jsonArray?.first()?.jsonPrimitive?.content)
        comp.stop()
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
