package github.rikacelery.v3.core

import github.rikacelery.v3.data.*
import github.rikacelery.v3.events.EndReason
import kotlinx.coroutines.CompletableDeferred
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
    private val awaiters = HashMap<Long, CompletableDeferred<Unit>>()

    suspend fun complete(idx: Long, result: DownloadResult) {
        mutex.withLock {
            buffer[idx] = result
            drain()
        }
    }

    /**
     * Completes [idx] and suspends until it has actually been emitted downstream.
     *
     * Cut points use this so the caller does not publish "cut done" while an in-flight
     * segment still holds the buffer: the next session's StreamStart must never reach the
     * writer before the previous session's StreamEnd.
     */
    suspend fun completeAndAwait(idx: Long, result: DownloadResult) {
        val emitted = CompletableDeferred<Unit>()
        mutex.withLock {
            awaiters[idx] = emitted
            buffer[idx] = result
            drain()
        }
        emitted.await()
    }

    private suspend fun drain() {
        while (buffer.isNotEmpty() && buffer.firstKey() == nextIndex) {
            val result = buffer.remove(nextIndex)!!
            emitIfSuccess(nextIndex, result)
            awaiters.remove(nextIndex)?.complete(Unit)
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
                // New architecture: CutPoint only closes the old file; the next StartRecording's StreamStart opens the new one
                val cut = result.cut
                output(StreamEnd(roomId, cut.reason))
            }
        }
    }
}
