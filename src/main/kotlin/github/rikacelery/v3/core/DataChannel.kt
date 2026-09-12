package github.rikacelery.v3.core

import github.rikacelery.v3.data.DataChannelMsg
import github.rikacelery.v3.hooks.DataHook
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
        if (BusMonitor.wants(BusMonitor.DATA)) {
            BusMonitor.record(BusMonitor.DATA, buildJsonObject {
                put("msg", msg::class.simpleName ?: "")
                roomOf(msg)?.let { put("room", it) }
                if (msg is github.rikacelery.v3.data.StreamData) put("bytes", msg.data.size)
            })
        }
        val result = channel.trySend(msg)
        val type = msg::class.simpleName ?: "unknown"
        if (result.isFailure) {
            // Dropping media here means the finished file is missing data, so it is counted as
            // well as logged — a log line nobody greps is not an alert.
            PipelineMetrics.recordChannelDropped(type)
            logger.warn("DataChannel full, dropping {} (roomId={})", type, roomOf(msg))
        } else {
            PipelineMetrics.channelPending.incrementAndGet()
            PipelineMetrics.recordChannelSent(
                type,
                if (msg is github.rikacelery.v3.data.StreamData) msg.data.size else 0
            )
        }
    }

    private fun roomOf(msg: DataChannelMsg): Long? = when (msg) {
        is github.rikacelery.v3.data.StreamStart -> msg.roomId
        is github.rikacelery.v3.data.StreamData -> msg.roomId
        is github.rikacelery.v3.data.StreamEnd -> msg.roomId
        is github.rikacelery.v3.data.StreamEvent -> msg.roomId
    }

    suspend fun receive(): DataChannelMsg {
        val msg = channel.receive()
        // Counted after the receive so a cancelled or closed receive does not decrement a message
        // that was never taken out of the channel.
        PipelineMetrics.channelPending.decrementAndGet()
        return msg
    }

    fun close() = channel.close()
}
