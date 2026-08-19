package github.rikacelery.v3.core

import github.rikacelery.v3.data.DataChannelMsg
import github.rikacelery.v3.hooks.DataHook
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory

class DataChannel(capacity: Int = 256) {
    private val logger = LoggerFactory.getLogger("v3.DataChannel")
    private val channel = Channel<DataChannelMsg>(capacity)
    private val hooks = mutableListOf<DataHook>()

    fun installHook(hook: DataHook) { hooks.add(hook) }

    /**
     * The stream pipeline is designed so the writer keeps up with the downloader;
     * a full channel therefore means a stalled writer. To avoid unbounded memory
     * growth we drop the message and log it instead of suspending indefinitely.
     */
    suspend fun send(msg: DataChannelMsg) {
        var m: DataChannelMsg? = msg
        for (hook in hooks) {
            m = hook.intercept(m ?: return)
        }
        val msg = m ?: return
        val result = channel.trySend(msg)
        if (result.isFailure) {
            logger.warn("DataChannel full, dropping {} (room={})", msg::class.simpleName, roomOf(msg))
        }
    }

    private fun roomOf(msg: DataChannelMsg): Long? = when (msg) {
        is github.rikacelery.v3.data.StreamStart -> msg.roomId
        is github.rikacelery.v3.data.StreamData -> msg.roomId
        is github.rikacelery.v3.data.StreamEnd -> msg.roomId
        is github.rikacelery.v3.data.StreamEvent -> msg.roomId
    }

    suspend fun receive(): DataChannelMsg = channel.receive()
    fun close() = channel.close()
}
