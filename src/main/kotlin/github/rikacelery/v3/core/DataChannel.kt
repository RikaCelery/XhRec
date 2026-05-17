package github.rikacelery.v3.core

import github.rikacelery.v3.data.DataChannelMsg
import github.rikacelery.v3.hooks.DataHook
import kotlinx.coroutines.channels.Channel

class DataChannel(capacity: Int = Channel.UNLIMITED) {
    private val channel = Channel<DataChannelMsg>(capacity)
    private val hooks = mutableListOf<DataHook>()

    fun installHook(hook: DataHook) { hooks.add(hook) }

    suspend fun send(msg: DataChannelMsg) {
        var m: DataChannelMsg? = msg
        for (hook in hooks) {
            m = hook.intercept(m ?: return)
        }
        if (m != null) channel.send(m!!)
    }

    suspend fun receive(): DataChannelMsg = channel.receive()
    fun close() = channel.close()
}
