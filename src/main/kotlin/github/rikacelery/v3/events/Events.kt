package github.rikacelery.v3.events

import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.time.Duration

data class Segment(val url: String, val index: Int)

// ── Room Events ──

data class RoomAdded(val roomId: Long, val name: String)
data class RoomRenamed(val roomId: Long, val oldName: String, val newName: String)
data class RoomRemoved(val roomId: Long, val name: String)
data class RoomStatusChanged(val roomId: Long, val oldStatus: String, val newStatus: String)

// ── Recording Events ──

data class RecordingStarted(val roomId: Long, val quality: String = "")
data class RecordingStopped(val roomId: Long, val segmentsDispatched: Int = 0)
data class FileReady(val roomId: Long, val file: File, val reason: EndReason, val roomName: String, val startTime: Long, val endTime: Long, val durationMs: Long, val quality: String)
data class FileProcessed(val roomId: Long, val file: File)

// ── Download Events ──

data class NewSegments(val roomId: Long, val urls: List<Segment>)
data class DownloadStarted(
    val roomId: Long,
    val idx: Int,
    val url: String,
    val timestamp: Long
)

data class SegmentDownloaded(
    val roomId: Long,
    val idx: Int,
    val originalUrl: String,
    val durationMs: Long,
    val proxied: Boolean,
    val bytes: Int,
    val generation: Int
)
data class DownloadError(
    val roomId: Long,
    val idx: Int?,
    val url: String?,
    val reason: String
)
data class SegmentGapDetected(
    val roomId: Long,
    val gap: Int
)
data class PlaylistRefreshed(
    val roomId: Long,
    val latencyMs: Long,
    val maxSegmentId: Int
)

// ── Platform Events ──

data class LiveMessage(val roomId: Long, val type: String, val body: JsonObject)

data class QualitiesAvailable(val roomId: Long, val qualities: List<String>)

// ── System Events ──

data class AuthExpired(val userId: Long)
data class WriterFatal(val roomId: Long, val error: String)
/** Published after config changes to trigger persistence */
object PersistConfig
/** Published when user manually changes quality — triggers immediate quality check */
data class QualityChangeRequested(val roomId: Long, val newQuality: String)
data class RoomTimeLimitChanged(val roomId: Long, val limit: Duration)
data class RoomSizeLimitChanged(val roomId: Long, val limitBytes: Long)

// ── Misc ──

enum class EndReason { SizeLimit, TimeLimit, StreamEnd, UserStop, NewInit }

interface Request
interface Response
