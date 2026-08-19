package github.rikacelery.v3.events

import kotlin.time.Duration

// ── Echo envelopes ──

data class CommandEnvelope(val id: Long, val command: Request) {
    override fun toString() = "CommandEnvelope(#$id $command)"
}
data class CommandAck(val requestId: Long, val body: Any) {
    override fun toString() = "CommandAck(#$requestId $body)"
}

// ── Room commands ──

data class GetRoomName(val roomId: Long) : Request {
    override fun toString() = "GetRoomName(roomId=$roomId)"
}
data class GetRoomConfig(val roomId: Long) : Request {
    override fun toString() = "GetRoomConfig(roomId=$roomId)"
}
data class SetRoomQuality(val roomId: Long, val quality: String) : Request {
    override fun toString() = "SetRoomQuality(roomId=$roomId, quality=$quality)"
}
data class SetRoomTimeLimit(val roomId: Long, val limit: Duration) : Request {
    override fun toString() = "SetRoomTimeLimit(roomId=$roomId, limit=$limit)"
}
data class SetRoomSizeLimit(val roomId: Long, val limitBytes: Long) : Request {
    override fun toString() = "SetRoomSizeLimit(roomId=$roomId, limitBytes=$limitBytes)"
}
data class SetRoomAutoPay(val roomId: Long, val autoPay: Boolean) : Request {
    override fun toString() = "SetRoomAutoPay(roomId=$roomId, autoPay=$autoPay)"
}
data class AddRoom(val name: String, val quality: String, val pkey: String = "", val timeLimit: Duration = Duration.INFINITE, val sizeLimitBytes: Long = 0, val autoPay: Boolean = false) : Request {
    override fun toString() = "AddRoom(name=$name, quality=$quality)"
}
data class RemoveRoom(val roomId: Long) : Request {
    override fun toString() = "RemoveRoom(roomId=$roomId)"
}

// ── Config commands ──

data class GetDecryptKey(val keyName: String) : Request {
    override fun toString() = "GetDecryptKey(keyName=$keyName)"
}
data class MatchDecryptKeys(val keys: List<String>) : Request {
    override fun toString() = "MatchDecryptKeys(count=${keys.size})"
}
object GetMaskStatus : Request {
    override fun toString() = "GetMaskStatus"
}
object ToggleMask : Request {
    override fun toString() = "ToggleMask"
}
object GetHostsConfig : Request {
    override fun toString() = "GetHostsConfig"
}
data class SetHostsConfig(val hosts: github.rikacelery.v3.data.HostsConfig) : Request {
    override fun toString() = "SetHostsConfig(platformHosts=${hosts.platformHosts}, ws=${hosts.webSocketHosts}, hls=${hosts.hlsHosts})"
}

// ── Downloader commands (Actor messages, not RequestBus) ──

data class Download(
    val roomId: Long,
    val urls: List<Segment>,
    val startIndex: Int,
    val generation: Int
) {
    override fun toString() = "Download(roomId=$roomId, idx=$startIndex, gen=$generation, count=${urls.size})"
}

data class CutPoint(
    val roomId: Long,
    val index: Int,
    val roomName: String,
    val startTime: java.time.Instant,
    val reason: EndReason,
    val quality: String = ""
) {
    override fun toString() = "CutPoint(roomId=$roomId, idx=$index, reason=$reason)"
}

// ── Session commands ──

/** Asks SessionComponent whether the room's stream is ready (master playlist reachable). */
data class ProbeStreamReady(val roomId: Long, val roomName: String, val quality: String) : Request {
    override fun toString() = "ProbeStreamReady(roomId=$roomId, name=$roomName)"
}

// ── Scheduler commands ──

data class ActivateRecordingCmd(val roomId: Long) : Request {
    override fun toString() = "ActivateRecordingCmd(roomId=$roomId)"
}
data class DeactivateCmd(val roomId: Long) : Request {
    override fun toString() = "DeactivateCmd(roomId=$roomId)"
}
data class BreakCmd(val roomId: Long, val reason: EndReason = EndReason.UserStop) : Request {
    override fun toString() = "BreakCmd(roomId=$roomId, reason=$reason)"
}

// ── Query commands ──

object GetRooms : Request {
    override fun toString() = "GetRooms"
}
object GetSessions : Request {
    override fun toString() = "GetSessions"
}
object GetArmedRoomIds : Request {
    override fun toString() = "GetArmedRoomIds"
}
object GetRoomDetailedStatus : Request {
    override fun toString() = "GetRoomDetailedStatus"
}
data class GetValidPaymentAccount(val price: Long) : Request {
    override fun toString() = "GetValidPaymentAccount(price=$price)"
}
data class DeductCoins(val userId: Long, val amount: Long) : Request {
    override fun toString() = "DeductCoins(userId=$userId, amount=$amount)"
}
object ShutdownCmd : Request {
    override fun toString() = "ShutdownCmd"
}
data class RefreshRoomCmd(val roomId: Long) : Request {
    override fun toString() = "RefreshRoomCmd(roomId=$roomId)"
}

// ── RequestBus responses ──

data class RoomNameResponse(val name: String) : Response {
    override fun toString() = "RoomNameResponse(name=$name)"
}
data class RoomConfigResponse(
    val quality: String,
    val timeLimit: Duration,
    val sizeLimitBytes: Long,
    val autoPay: Boolean,
    val pkey: String = ""
) : Response {
    override fun toString() = "RoomConfigResponse(quality=$quality, timeLimit=$timeLimit, sizeLimitBytes=$sizeLimitBytes)"
}
data class ConfigResponse(val value: Any?) : Response {
    override fun toString() = "ConfigResponse(value=$value)"
}
data class HostsConfigResponse(val hosts: github.rikacelery.v3.data.HostsConfig) : Response {
    override fun toString() = "HostsConfigResponse(platformHosts=${hosts.platformHosts})"
}
data class DecryptKeyMatch(val keyName: String, val decryptKey: String) : Response {
    override fun toString() = "DecryptKeyMatch(keyName=$keyName)"
}
object OkResponse : Response {
    override fun toString() = "OkResponse"
}
data class ErrorResponse(val message: String) : Response {
    override fun toString() = "ErrorResponse(message=$message)"
}
