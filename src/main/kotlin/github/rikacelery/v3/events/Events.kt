package github.rikacelery.v3.events

import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.time.Duration

data class Segment(val url: String, val index: Int) {
    override fun toString() = "Segment(#$index $url)"
}

// ── Room Events ──

data class RoomAdded(val roomId: Long, val name: String) {
    override fun toString() = "RoomAdded(roomId=$roomId, name=$name)"
}
data class RoomRenamed(val roomId: Long, val oldName: String, val newName: String) {
    override fun toString() = "RoomRenamed(roomId=$roomId, $oldName → $newName)"
}
data class RoomRemoved(val roomId: Long, val name: String) {
    override fun toString() = "RoomRemoved(roomId=$roomId, name=$name)"
}
data class RoomStatusChanged(val roomId: Long, val oldStatus: String, val newStatus: String) {
    override fun toString() = "RoomStatusChanged(roomId=$roomId, $oldStatus → $newStatus)"
}

// ── Recording Events ──

data class RecordingStarted(val roomId: Long, val quality: String = "") {
    override fun toString() = "RecordingStarted(roomId=$roomId, quality=$quality)"
}
data class RecordingStopped(val roomId: Long) {
    override fun toString() = "RecordingStopped(roomId=$roomId)"
}
data class FileReady(val roomId: Long, val file: File, val reason: EndReason, val roomName: String, val startTime: Long, val endTime: Long, val durationMs: Long, val quality: String) {
    override fun toString() = "FileReady(roomId=$roomId, file=${file.name}, reason=$reason, duration=${durationMs}ms)"
}
data class FileProcessed(val roomId: Long, val file: File) {
    override fun toString() = "FileProcessed(roomId=$roomId, file=${file.name})"
}

// ── Download Events ──

data class NewSegments(val roomId: Long, val urls: List<Segment>) {
    override fun toString() = "NewSegments(roomId=$roomId, count=${urls.size})"
}
data class DownloadStarted(
    val roomId: Long,
    val idx: Int,
    val url: String,
    val timestamp: Long
) {
    override fun toString() = "DownloadStarted(roomId=$roomId, idx=$idx)"
}
data class SegmentDownloaded(
    val roomId: Long,
    val idx: Int,
    val originalUrl: String,
    val durationMs: Long,
    val proxied: Boolean,
    val bytes: Int,
    val generation: Int
) {
    override fun toString() = "SegmentDownloaded(roomId=$roomId, idx=$idx, bytes=$bytes, duration=${durationMs}ms, gen=$generation)"
}
data class DownloadError(
    val roomId: Long,
    val idx: Int?,
    val url: String?,
    val reason: String
) {
    override fun toString() = "DownloadError(roomId=$roomId, idx=$idx, reason=$reason)"
}
data class SegmentGapDetected(
    val roomId: Long,
    val gap: Int
) {
    override fun toString() = "SegmentGapDetected(roomId=$roomId, gap=$gap)"
}
data class PlaylistRefreshed(
    val roomId: Long,
    val latencyMs: Long,
    val maxSegmentId: Int
) {
    override fun toString() = "PlaylistRefreshed(roomId=$roomId, latency=${latencyMs}ms, maxSeg=$maxSegmentId)"
}

// ── Platform Events ──

data class LiveMessage(val roomId: Long, val type: String, val body: JsonObject) {
    override fun toString() = "LiveMessage(roomId=$roomId, type=$type)"
}

data class QualitiesAvailable(val roomId: Long, val qualities: List<String>) {
    override fun toString() = "QualitiesAvailable(roomId=$roomId, qualities=$qualities)"
}

// ── System Events ──

data class AuthExpired(val userId: Long) {
    override fun toString() = "AuthExpired(userId=$userId)"
}
data class WriterFatal(val roomId: Long, val error: String) {
    override fun toString() = "WriterFatal(roomId=$roomId, error=$error)"
}
/** Published after config changes to trigger persistence */
object PersistConfig {
    override fun toString() = "PersistConfig"
}
/** Published when the host configuration changes (LiveEventSource reconnects on this) */
object HostsChanged {
    override fun toString() = "HostsChanged"
}
/** Published when the platform WebSocket connection is lost / established. */
object WsDisconnected {
    override fun toString() = "WsDisconnected"
}
object WsReconnected {
    override fun toString() = "WsReconnected"
}
/** Published when the WS reports a broadcast-settings / stream change — a hint to re-check quality */
data class QualityChangeHint(val roomId: Long) {
    override fun toString() = "QualityChangeHint(roomId=$roomId)"
}
/** Published when user manually changes quality — triggers immediate quality check */
data class QualityChangeRequested(val roomId: Long, val newQuality: String) {
    override fun toString() = "QualityChangeRequested(roomId=$roomId, quality=$newQuality)"
}
data class RoomTimeLimitChanged(val roomId: Long, val limit: Duration) {
    override fun toString() = "RoomTimeLimitChanged(roomId=$roomId, limit=$limit)"
}
data class RoomSizeLimitChanged(val roomId: Long, val limitBytes: Long) {
    override fun toString() = "RoomSizeLimitChanged(roomId=$roomId, limitBytes=$limitBytes)"
}

// ── Misc ──

enum class EndReason { SizeLimit, TimeLimit, StreamEnd, UserStop, NewInit }

interface Request
interface Response
