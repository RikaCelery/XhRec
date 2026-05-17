package github.rikacelery.v3.events

// ── Echo envelopes ──

data class CommandEnvelope(val id: Long, val command: Request)
data class CommandAck(val requestId: Long, val body: Any)

// ── Room commands ──

data class GetRoomName(val roomId: Long) : Request
data class GetRoomStatus(val roomId: Long) : Request
data class GetRoomConfig(val roomId: Long) : Request
data class SetRoomQuality(val roomId: Long, val quality: String) : Request
data class SetRoomTimeLimit(val roomId: Long, val limitMs: Long) : Request
data class SetRoomSizeLimit(val roomId: Long, val limitBytes: Long) : Request
data class SetRoomAutoPay(val roomId: Long, val autoPay: Boolean) : Request
data class AddRoom(val name: String, val quality: String) : Request
data class RemoveRoom(val roomId: Long) : Request

// ── Config commands ──

object GetOutputDir : Request
object GetTmpDir : Request
object GetProxy : Request
data class GetDecryptKey(val keyName: String) : Request
object GetPlatformHost : Request
object GetPostProcessors : Request

// ── Scheduler commands ──

data class StartRecordingCmd(val roomId: Long) : Request
data class StopRecordingCmd(val roomId: Long) : Request
data class PauseRecordingCmd(val roomId: Long) : Request
data class ResumeRecordingCmd(val roomId: Long) : Request

// ── Downloader commands (Actor messages, not RequestBus) ──

data class Download(
    val roomId: Long,
    val urls: List<Segment>,
    val startIndex: Int
)

data class CutPoint(
    val roomId: Long,
    val index: Int,
    val roomName: String,
    val startTime: java.time.Instant,
    val reason: EndReason
)

data class StopFetch(val roomId: Long)

// ── Scheduler extended commands ──

data class ActivateRecordingCmd(val roomId: Long) : Request
data class DeactivateCmd(val roomId: Long) : Request
data class BreakCmd(val roomId: Long) : Request

// ── Query commands ──

object GetRooms : Request
object GetSessions : Request
object ShutdownCmd : Request

// ── RequestBus responses ──

data class RoomNameResponse(val name: String) : Response
data class RoomStatusResponse(val status: String) : Response
data class RoomConfigResponse(
    val quality: String,
    val timeLimitMs: Long,
    val sizeLimitBytes: Long,
    val autoPay: Boolean
) : Response
data class ConfigResponse(val value: Any?) : Response
object OkResponse : Response
data class ErrorResponse(val message: String) : Response
