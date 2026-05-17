package github.rikacelery.v3.components

import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.data.SystemConfig
import github.rikacelery.v3.events.*
import kotlinx.coroutines.CoroutineScope

sealed interface ConfigMsg
data class HandleConfigQuery(val env: CommandEnvelope) : ConfigMsg

class ConfigComponent(
    private val config: SystemConfig,
    eventBus: EventBus,
    parentScope: CoroutineScope
) : Actor<ConfigMsg>("ConfigComponent", eventBus, parentScope) {

    override suspend fun onStart(scope: CoroutineScope) {
        subscribe<CommandEnvelope>(CommandEnvelope::class)
    }

    override suspend fun wrapEvent(event: Any): ConfigMsg? = when (event) {
        is CommandEnvelope -> HandleConfigQuery(event)
        else -> null
    }

    override suspend fun handle(msg: ConfigMsg) = when (msg) {
        is HandleConfigQuery -> handleQuery(msg.env)
    }

    private suspend fun handleQuery(env: CommandEnvelope) {
        val ack = when (env.command) {
            is GetOutputDir -> ConfigResponse(config.outputDir)
            is GetTmpDir -> ConfigResponse(config.tmpDir)
            is GetProxy -> ConfigResponse(config.proxy)
            is GetDecryptKey -> ConfigResponse(config.decryptKeys[env.command.keyName])
            is GetPlatformHost -> ConfigResponse(config.platformHost)
            else -> return
        }
        eventBus.publish(CommandAck(env.id, ack))
    }
}
