package github.rikacelery.v3.components

import github.rikacelery.v3.api.ApiClient
import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.data.Hosts
import github.rikacelery.v3.data.HostsConfig
import github.rikacelery.v3.data.SystemConfig
import github.rikacelery.v3.events.*
import github.rikacelery.v3.utils.CdnSelector
import github.rikacelery.v3.utils.LogLevels
import github.rikacelery.v3.utils.SensitiveStringRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
sealed interface ConfigMsg
data class HandleConfigQuery(val env: CommandEnvelope) : ConfigMsg

class ConfigComponent(
    val config: SystemConfig,
    private val apiClient: ApiClient,
    eventBus: EventBus,
    parentScope: CoroutineScope
) : Actor<ConfigMsg>("ConfigComponent", eventBus, parentScope) {

    private val configFile = File(config.configPath)
    private var persistedStreamAuthKey: String = config.streamAuthKey
    private val persistedDecryptKeys = config.decryptKeys.toMutableMap()
    private var maskSensitiveLogs = config.maskSensitiveLogs
    private var apiToken: String = config.apiToken
    /** Last log level chosen in the dashboard; `""` means "whatever logback.xml sets". */
    private var logLevel: String = config.logLevel
    private var hostsConfig: HostsConfig = config.hosts
    /** Serializes writes: the startup save and a config-change save may otherwise interleave. */
    private val saveLock = Mutex()

    private suspend fun loadConfig() {
        if (!configFile.exists()) return
        try {
            val json = withContext(Dispatchers.IO) {
                Json.parseToJsonElement(configFile.readText()).jsonObject
            }
            json["streamAuthKey"]?.jsonPrimitive?.content?.let { persistedStreamAuthKey = it }
            json["decryptKeys"]?.jsonObject?.forEach { (k, v) ->
                persistedDecryptKeys[k] = v.jsonPrimitive.content
            }
            json["maskSensitiveLogs"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()?.let { maskSensitiveLogs = it }
            json["apiToken"]?.jsonPrimitive?.content?.let { apiToken = it }
            json["logLevel"]?.jsonPrimitive?.content?.let { logLevel = it }
            hostsConfig = HostsConfig.fromJson(json)
            SensitiveStringRegistry.enabled = maskSensitiveLogs
            applyLogLevel()
            applyHosts()
            logger.info("Loaded config from ${config.configPath}")
        } catch (e: Exception) {
            logger.error("Failed to load config from ${config.configPath}: ${e.message}", e)
        }
    }

    private suspend fun saveConfig() {
        // Serialize saves and read the fields inside the lock: a save that started earlier
        // must never overwrite a newer state with stale values.
        saveLock.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val json = Json { prettyPrint = true }
                    configFile.writeText(
                        json.encodeToString(
                            JsonElement.serializer(),
                            buildJsonObject {
                                put("streamAuthKey", persistedStreamAuthKey)
                                put("maskSensitiveLogs", maskSensitiveLogs)
                                put("apiToken", apiToken)
                                put("logLevel", logLevel)
                                hostsConfig.toJson().forEach { (k, v) -> put(k, v) }
                                put("decryptKeys", buildJsonObject {
                                    persistedDecryptKeys.forEach { (k, v) -> put(k, v) }
                                })
                            }
                        )
                    )
                    logger.info("Saved config to ${config.configPath}")
                } catch (e: Exception) {
                    logger.error("Failed to save config to ${config.configPath}: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Wait for an in-flight [saveConfig] to finish before tearing the scope down. The save writes on
     * [Dispatchers.IO]; cancelling it mid-write leaves a truncated xhrec.json and races callers that
     * delete the working directory (the integration tests' temp dirs).
     */
    override fun stop() {
        runBlocking { saveLock.withLock { } }
        super.stop()
    }

    /** Re-apply the persisted log level; a blank value leaves logback.xml in charge. */
    private fun applyLogLevel() {
        if (logLevel.isBlank()) return
        val applied = LogLevels.apply(logLevel)
        if (applied == null) {
            logger.warn("Ignoring unknown persisted log level '{}'", logLevel)
            logLevel = ""
        } else {
            logLevel = applied
            logger.info("Applied persisted log level {}", applied)
        }
    }

    /** Push the active host config to all consumers and notify components that need to react. */
    private suspend fun applyHosts() {
        val cfg = HostsConfig.sanitize(hostsConfig)
        hostsConfig = cfg
        Hosts.current = cfg
        apiClient.applyHosts(cfg.platformHosts)
        CdnSelector.updateHosts(cfg.hlsHosts)
        eventBus.publish(HostsChanged)
        logger.info("Hosts config applied: platform=${cfg.platformHosts}, ws=${cfg.webSocketHosts}, hls=${cfg.hlsHosts}, master=${cfg.hlsMasterHost}")
    }

    override suspend fun onStart(scope: CoroutineScope) {
        subscribe<CommandEnvelope>(CommandEnvelope::class)
        subscribe<PersistConfig>(PersistConfig::class)
        loadConfig() // refresh from disk in case Main.kt already loaded
        scope.launch { saveConfig() } // ensure config file exists
    }

    override suspend fun wrapEvent(event: Any): ConfigMsg? = when (event) {
        is CommandEnvelope -> HandleConfigQuery(event)
        is PersistConfig -> {
            scope.launch(Dispatchers.IO) { saveConfig() }
            null
        }

        else -> null
    }

    override suspend fun handle(msg: ConfigMsg) = when (msg) {
        is HandleConfigQuery -> handleQuery(msg.env)
    }

    private suspend fun handleQuery(env: CommandEnvelope) {
        val ack = when (env.command) {
            is GetDecryptKey -> ConfigResponse(persistedDecryptKeys[env.command.keyName])
            is MatchDecryptKeys -> {
                val found = env.command.keys.firstOrNull { persistedDecryptKeys.containsKey(it) }
                if (found != null) DecryptKeyMatch(found, persistedDecryptKeys[found]!!)
                else DecryptKeyMatch("", "")
            }

            is GetMaskStatus -> ConfigResponse(maskSensitiveLogs)
            is ToggleMask -> {
                maskSensitiveLogs = !maskSensitiveLogs
                SensitiveStringRegistry.enabled = maskSensitiveLogs
                ConfigResponse(maskSensitiveLogs)
            }

            is GetHostsConfig -> HostsConfigResponse(HostsConfig.sanitize(hostsConfig))
            is SetHostsConfig -> {
                hostsConfig = HostsConfig.sanitize(env.command.hosts)
                applyHosts()
                scope.launch(Dispatchers.IO) { saveConfig() }
                OkResponse
            }

            // The effective level is read back from Logback rather than from `logLevel`, so the
            // reported value is what is actually filtering logs even when logback.xml set it.
            is GetLogLevel -> ConfigResponse(LogLevels.currentOrDefault())
            is SetLogLevel -> {
                val applied = LogLevels.apply(env.command.level)
                if (applied == null) {
                    logger.warn("Rejected unknown log level '{}'", env.command.level)
                    ConfigResponse(null)
                } else {
                    logLevel = applied
                    logger.info("Runtime log level set to {}", applied)
                    scope.launch(Dispatchers.IO) { saveConfig() }
                    ConfigResponse(applied)
                }
            }

            else -> return
        }
        eventBus.publish(CommandAck(env.id, ack))
    }

    // —— Diagnostics ——

    /**
     * `/diagnose?actor=ConfigComponent`
     *
     * Secrets are reported as presence and length only — the auth key, API token and decrypt keys
     * must never leave the process through a debug endpoint.
     */
    override suspend fun diagnose(section: String, args: Map<String, String>): JsonObject =
        baseDiagnose(buildJsonObject {
            put("maskSensitiveLogs", maskSensitiveLogs)
            put("logLevel", logLevel.ifBlank { "(logback.xml)" })
            put("effectiveLogLevel", LogLevels.currentOrDefault())
            put("configPath", config.configPath)
            put("hosts", JsonObject(hostsConfig.toJson()))
            put("streamAuthKey", buildJsonObject {
                put("present", persistedStreamAuthKey.isNotBlank())
                put("length", persistedStreamAuthKey.length)
            })
            put("apiToken", buildJsonObject {
                put("present", apiToken.isNotBlank())
                put("length", apiToken.length)
            })
            put("decryptKeys", buildJsonArray {
                persistedDecryptKeys.keys.forEach { add(JsonPrimitive(it)) }
            })
        })
}
