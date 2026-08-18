package github.rikacelery.v3.core

import github.rikacelery.v3.data.*
import github.rikacelery.v3.events.EndReason
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class OrderedEmitter(
    private val roomId: Long,
    private val output: suspend (DataChannelMsg) -> Unit
) {
    // complete() is called concurrently from downloader workers; the buffer and
    // nextIndex must be mutated under a single lock (drain may suspend on output()).
    private val mutex = Mutex()
    private var nextIndex = 0L
    private val buffer = sortedMapOf<Long, DownloadResult>()

    suspend fun complete(idx: Long, result: DownloadResult) {
        mutex.withLock {
            buffer[idx] = result
            drain()
        }
    }

    private suspend fun drain() {
        while (buffer.isNotEmpty() && buffer.firstKey() == nextIndex) {
            val result = buffer.remove(nextIndex)!!
            emitIfSuccess(nextIndex, result)
            nextIndex++

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

            is DownloadResult.Failed -> { /* skip */
            }

            is DownloadResult.CutPoint -> {
                val cut = result.cut
                output(StreamEnd(roomId, cut.reason))
                if (cut.reason != EndReason.UserStop)
                    output(StreamStart(roomId, cut.roomName, cut.startTime, cut.quality))
            }
        }
    }
}
