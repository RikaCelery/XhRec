package github.rikacelery.v3.core

import github.rikacelery.v3.data.*
import github.rikacelery.v3.events.EndReason
import java.time.Instant

class OrderedEmitter(
    private val roomId: Long,
    private val output: suspend (DataChannelMsg) -> Unit
) {
    private var nextIndex = 0L
    private val buffer = sortedMapOf<Long, DownloadResult>()
    private var cutPending: CutState? = null

    private data class CutState(
        val index: Long,
        val roomName: String,
        val startTime: Instant,
        val reason: EndReason
    )

    suspend fun complete(idx: Long, result: DownloadResult) {
        buffer[idx] = result
        drain()
    }

    fun signalCut(index: Int, roomName: String, startTime: Instant, reason: EndReason) {
        buffer.keys.removeAll { it > index }
        cutPending = CutState(index.toLong(), roomName, startTime, reason)
    }

    fun reset(fromIndex: Int) {
        buffer.clear()
        cutPending = null
        nextIndex = fromIndex.toLong()
    }

    private suspend fun drain() {
        while (buffer.isNotEmpty() && buffer.firstKey() == nextIndex) {
            val result = buffer.remove(nextIndex)!!
            emitIfSuccess(nextIndex, result)
            nextIndex++

            if (cutPending != null && nextIndex > cutPending!!.index) {
                val cut = cutPending!!
                cutPending = null
                output(StreamEnd(roomId, cut.reason))
                output(StreamStart(roomId, cut.roomName, cut.startTime))
                nextIndex = 0
            }
        }
    }

    private suspend fun emitIfSuccess(idx: Long, result: DownloadResult) {
        when (result) {
            is DownloadResult.Success -> {
                output(
                    StreamData(
                        roomId = roomId,
                        data = result.data,
                        segmentIndex = idx.toInt(),
                        meta = result.meta
                    )
                )
            }
            is DownloadResult.Failed -> { /* skip */ }
            is DownloadResult.Skipped -> { /* skip */ }
        }
    }
}
