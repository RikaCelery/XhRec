package github.rikacelery.v3.events

import kotlin.time.Duration

// ── Echo envelopes ──

data class CommandEnvelope(val id: Long, val command: Request)
data class CommandAck(val requestId: Long, val body: Any)

// ── Room commands ──

data class GetRoomName(val roomId: Long) : Request

data class GetRoomConfig(val roomId: Long) : Request
data class SetRoomQuality(val roomId: Long, val quality: String) : Request
data class SetRoomTimeLimit(val roomId: Long, val limit: Duration) : Request
data class SetRoomSizeLimit(val roomId: Long, val limitBytes: Long) : Request
data class SetRoomAutoPay(val roomId: Long, val autoPay: Boolean) : Request
data class AddRoom(val name: String, val quality: String, val pkey: String = "", val timeLimit: Duration = Duration.INFINITE, val sizeLimitBytes: Long = 0, val autoPay: Boolean = false) : Request
data class RemoveRoom(val roomId: Long) : Request

// ── Config commands ──

data class GetDecryptKey(val keyName: String) : Request
data class MatchDecryptKeys(val keys: List<String>) : Request
object GetMaskStatus : Request
object ToggleMask : Request
// ── Scheduler commands ──

data class StartRecordingCmd(val roomId: Long) : Request
data class StopRecordingCmd(val roomId: Long) : Request
// ── Downloader commands (Actor messages, not RequestBus) ──

data class Download(
    val roomId: Long,
    val urls: List<Segment>,
    val startIndex: Int,
    val generation: Int
)

data class CutPoint(
    val roomId: Long,
    val index: Int,
    val roomName: String,
    val startTime: java.time.Instant,
    val reason: EndReason,
    val quality: String = ""
)

// ── Scheduler extended commands ──

data class ActivateRecordingCmd(val roomId: Long) : Request
data class DeactivateCmd(val roomId: Long) : Request
data class BreakCmd(val roomId: Long, val reason: EndReason = EndReason.UserStop) : Request

// ── Query commands ──

object GetRooms : Request
object GetSessions : Request
object GetArmedRoomIds : Request
object GetRoomDetailedStatus : Request
data class GetValidPaymentAccount(val price: Long) : Request
data class DeductCoins(val userId: Long, val amount: Long) : Request
object ShutdownCmd : Request
data class RefreshRoomCmd(val roomId: Long) : Request

// ── RequestBus responses ──

data class RoomNameResponse(val name: String) : Response
data class RoomConfigResponse(
    val quality: String,
    val timeLimit: Duration,
    val sizeLimitBytes: Long,
    val autoPay: Boolean,
    val pkey: String = ""
) : Response
data class ConfigResponse(val value: Any?) : Response
data class DecryptKeyMatch(val keyName: String, val decryptKey: String) : Response
object OkResponse : Response
data class ErrorResponse(val message: String) : Response
