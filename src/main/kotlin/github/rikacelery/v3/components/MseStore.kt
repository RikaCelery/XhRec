package github.rikacelery.v3.components

import github.rikacelery.v3.data.DataChannelMsg
import github.rikacelery.v3.data.StreamData
import github.rikacelery.v3.data.StreamEnd
import github.rikacelery.v3.data.StreamStart
import github.rikacelery.v3.hooks.DataHook
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.MutableList
import kotlin.collections.isEmpty
import kotlin.collections.isNotEmpty
import kotlin.collections.mutableListOf
import kotlin.collections.toList

class MseStore : DataHook {

    data class SegmentEntry(
        val idx: Int,
        val data: ByteArray,
        val generation: Int
    )

    sealed class SseChunk {
        data class Meta(val mime: String) : SseChunk()
        data class Init(val data: ByteArray) : SseChunk()
        data class Seg(val data: ByteArray) : SseChunk()
    }

    private data class RoomState(
        @Volatile var latestSegment: SegmentEntry? = null,
        @Volatile var generation: Int = 0,
        @Volatile var init: ByteArray = ByteArray(0),
        @Volatile var mime: String = "",
        @Volatile var lastAccess: Long = System.currentTimeMillis(),
        val segCounter: AtomicInteger = AtomicInteger(0),
        @Volatile var latestIdx: Int = -1,
        val sseChannels: MutableList<SendChannel<SseChunk>> = Collections.synchronizedList(mutableListOf())
    )

    private val rooms = ConcurrentHashMap<Long, RoomState>()

    // ── DataHook ────────────────────────────────────────────────────
    override suspend fun intercept(msg: DataChannelMsg): DataChannelMsg {
        when (msg) {
            is StreamStart -> onStreamStart(msg.roomId)
            is StreamData -> onStreamData(msg)
            is StreamEnd -> onStreamEnd(msg.roomId)
            else -> {}
        }
        return msg
    }

    private fun onStreamStart(roomId: Long) {
        val state = rooms.computeIfAbsent(roomId) { RoomState() }
        state.generation++
        state.latestSegment = null
        state.segCounter.set(0)
        state.latestIdx = -1
        state.lastAccess = System.currentTimeMillis()
    }

    private fun onStreamData(msg: StreamData) {
        val state = rooms.computeIfAbsent(msg.roomId) { RoomState() }
        state.lastAccess = System.currentTimeMillis()

        val init = isInitSegment(msg)
        if (init) {
            state.init = msg.data
            state.mime = extractMime(msg.data)
            state.segCounter.set(0)
            state.latestIdx = -1
            state.latestSegment = null
            broadcast(state, SseChunk.Meta(state.mime))
            broadcast(state, SseChunk.Init(msg.data))
        } else {
            val idx = state.segCounter.incrementAndGet()
            state.latestIdx = idx
            state.latestSegment = SegmentEntry(idx, msg.data, state.generation)
            broadcast(state, SseChunk.Seg(msg.data))
        }
    }

    private fun broadcast(state: RoomState, chunk: SseChunk) {
        for (ch in state.sseChannels.toList()) { // snapshot, no lock held during trySend
            ch.trySend(chunk)
        }
    }

    private fun onStreamEnd(roomId: Long) {
        rooms[roomId]?.generation?.inc()?.let { rooms[roomId]?.generation = it }
        // terminate open SSE streams so their HTTP connections close instead of lingering
        rooms[roomId]?.sseChannels?.forEach { it.close() }
        rooms[roomId]?.sseChannels?.clear()
    }

    // ── SSE streaming ───────────────────────────────────────────────
    fun subscribe(roomId: Long): Channel<SseChunk> {
        // bounded so slow/disconnected clients can't accumulate an unbounded backlog
        val ch = Channel<SseChunk>(capacity = 64)
        val state = rooms.computeIfAbsent(roomId) { RoomState() }
        state.sseChannels.add(ch)

        // catch-up: replay meta + init + latest segment
        if (state.init.isNotEmpty()) {
            ch.trySend(SseChunk.Meta(state.mime))
            ch.trySend(SseChunk.Init(state.init))
            state.latestSegment?.let { ch.trySend(SseChunk.Seg(it.data)) }
        }
        return ch
    }

    fun unsubscribe(roomId: Long, ch: Channel<SseChunk>) {
        rooms[roomId]?.sseChannels?.remove(ch)
    }

    private fun isInitSegment(msg: StreamData): Boolean = msg.meta.url.contains("_init_")

    private fun extractMime(init: ByteArray): String {
        var i = 0
        while (i + 12 <= init.size) {
            val sz = ((init[i].toInt() and 0xFF) shl 24) or
                ((init[i + 1].toInt() and 0xFF) shl 16) or
                ((init[i + 2].toInt() and 0xFF) shl 8) or
                (init[i + 3].toInt() and 0xFF)
            val tp = String(init, i + 4, 4)
            if (tp == "avcC") {
                return "avc1.%02X%02X%02X".format(
                    init[i + 9].toInt() and 0xFF,
                    init[i + 10].toInt() and 0xFF,
                    init[i + 11].toInt() and 0xFF
                )
            }
            if (tp == "hvcC" && i + 18 <= init.size) {
                return "hvc1.%X.%X.%X".format(
                    init[i + 13].toInt() and 0xFF,
                    init[i + 15].toInt() and 0xFF,
                    init[i + 17].toInt() and 0xFF
                )
            }
            i += if (sz > 0) sz else init.size - i
        }
        return "avc1.64001F"
    }
}
