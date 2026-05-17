package github.rikacelery.v3.hooks

import github.rikacelery.v3.events.CommandAck
import github.rikacelery.v3.events.CommandEnvelope

interface CommandHook {
    suspend fun beforeRequest(cmd: CommandEnvelope): CommandEnvelope?
    suspend fun beforeAck(ack: CommandAck): CommandAck?
}
