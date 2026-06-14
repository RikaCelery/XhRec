package github.rikacelery.v3.components

import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.data.SystemConfig
import github.rikacelery.v3.events.*
import github.rikacelery.v3.utils.SensitiveStringRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

sealed interface ConfigMsg
data class HandleConfigQuery(val env: CommandEnvelope) : ConfigMsg

class ConfigComponent(
    private val config: SystemConfig,
    eventBus: EventBus,
    parentScope: CoroutineScope
) : Actor<ConfigMsg>("ConfigComponent", eventBus, parentScope) {

    private val configFile = File(config.configPath)
    private var persistedStreamAuthKey: String = config.streamAuthKey
    private val persistedDecryptKeys = config.decryptKeys.toMutableMap()
    private var maskSensitiveLogs = config.maskSensitiveLogs

    private fun loadConfig() {
        if (!configFile.exists()) return
        try {
            val json = Json.parseToJsonElement(configFile.readText()).jsonObject
            json["streamAuthKey"]?.jsonPrimitive?.content?.let { persistedStreamAuthKey = it }
            json["decryptKeys"]?.jsonObject?.forEach { (k, v) ->
                persistedDecryptKeys[k] = v.jsonPrimitive.content
            }
            json["maskSensitiveLogs"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()?.let { maskSensitiveLogs = it }
            SensitiveStringRegistry.enabled = maskSensitiveLogs
            logger.info("Loaded config from ${config.configPath}")
        } catch (e: Exception) {
            logger.warn("Failed to load config.json: ${e.message}")
        }
    }

    private fun saveConfig() {
        try {
            val json = Json { prettyPrint = true }
            configFile.writeText(json.encodeToString(JsonElement.serializer(),
                buildJsonObject {
                    put("streamAuthKey", persistedStreamAuthKey)
                    put("maskSensitiveLogs", maskSensitiveLogs)
                    put("decryptKeys", buildJsonObject {
                        persistedDecryptKeys.forEach { (k, v) -> put(k, v) }
                    })
                }
            ))
            logger.info("Saved config to ${config.configPath}")
        } catch (e: Exception) {
            logger.warn("Failed to save config.json: ${e.message}")
        }
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
            saveConfig()
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
            else -> return
        }
        eventBus.publish(CommandAck(env.id, ack))
    }
}
