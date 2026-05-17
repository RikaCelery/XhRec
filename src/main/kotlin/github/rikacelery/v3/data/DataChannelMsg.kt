package github.rikacelery.v3.data

import github.rikacelery.v3.events.EndReason
import java.time.Instant

sealed interface DataChannelMsg

data class StreamStart(
    val roomId: Long,
    val roomName: String,
    val startTime: Instant
) : DataChannelMsg

data class StreamData(
    val roomId: Long,
    val data: ByteArray,
    val segmentIndex: Int,
    val meta: DownloadMeta
) : DataChannelMsg {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StreamData) return false
        return roomId == other.roomId &&
            segmentIndex == other.segmentIndex &&
            data.contentEquals(other.data) &&
            meta == other.meta
    }
    override fun hashCode(): Int {
        var result = roomId.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + segmentIndex
        result = 31 * result + meta.hashCode()
        return result
    }
}

data class StreamEnd(
    val roomId: Long,
    val reason: EndReason
) : DataChannelMsg

data class StreamEvent(
    val roomId: Long,
    val timestamp: Instant,
    val eventJson: String
) : DataChannelMsg

data class DownloadMeta(
    val url: String,
    val fetchDurationMs: Long,
    val proxied: Boolean,
    val timestamp: Instant
)
