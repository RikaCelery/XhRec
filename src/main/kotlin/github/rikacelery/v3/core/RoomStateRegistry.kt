package github.rikacelery.v3.core

import java.util.concurrent.ConcurrentHashMap

/**
 * The per-room state the exporter renders, pushed by the components that own it.
 *
 * Pushed rather than pulled on purpose: `/metrics` must never block. Asking the scheduler and the
 * sessions over the request bus at scrape time would make exactly the room panels an operator needs
 * go blank when the process is saturated — the one moment they matter. Each component writes its
 * own slice, so no component has to know about the others.
 *
 * Values are plain strings and numbers: the registry deliberately does not depend on the domain
 * packages, so components translate their own state (an FSM state, a hint code) before pushing.
 *
 * Room **names** are deliberately absent. They are masked in the logs by default
 * (`maskSensitiveLogs`), and metric labels do not go through that converter, so exporting them
 * would hand model names to anyone who can read the scrape — and to whatever stores it.
 */
object RoomStateRegistry {

    class RoomState {
        @Volatile var status: String = ""
        @Volatile var quality: String = ""
        @Volatile var armed: Boolean = false
        @Volatile var schedulerState: String = ""
        @Volatile var hintCode: String? = null
        @Volatile var sessionState: String = ""
        @Volatile var lastProgressAtMs: Long = 0L
        @Volatile var resumeMarkAhead: Long = 0L
    }

    private val rooms = ConcurrentHashMap<Long, RoomState>()

    fun update(roomId: Long, block: RoomState.() -> Unit) {
        rooms.computeIfAbsent(roomId) { RoomState() }.block()
    }

    /** Drops every room the caller no longer knows about, so removed rooms stop being exported. */
    fun retainRooms(roomIds: Collection<Long>) {
        rooms.keys.retainAll(roomIds.toSet())
    }

    fun remove(roomId: Long) {
        rooms.remove(roomId)
    }

    fun appendMetrics(sb: StringBuilder, nowMs: Long = System.currentTimeMillis()) {
        if (rooms.isEmpty()) return

        family(sb, "xhrec_room_info", "A tracked room, with its platform status and quality", "gauge")
        rooms.forEach { (id, room) ->
            sb.appendLine(
                "xhrec_room_info{roomId=\"$id\",status=\"${escape(room.status)}\"," +
                    "quality=\"${escape(room.quality)}\"} 1"
            )
        }

        family(sb, "xhrec_room_armed", "The room is armed for recording (1) or not (0)", "gauge")
        rooms.forEach { (id, room) -> sb.appendLine("xhrec_room_armed{roomId=\"$id\"} ${if (room.armed) 1 else 0}") }

        family(sb, "xhrec_scheduler_state", "Scheduler state machine state, as an info label", "gauge")
        rooms.forEach { (id, room) ->
            if (room.schedulerState.isNotEmpty()) {
                sb.appendLine("xhrec_scheduler_state{roomId=\"$id\",state=\"${escape(room.schedulerState)}\"} 1")
            }
        }

        family(sb, "xhrec_session_state", "Recording session state machine state, as an info label", "gauge")
        rooms.forEach { (id, room) ->
            if (room.sessionState.isNotEmpty()) {
                sb.appendLine("xhrec_session_state{roomId=\"$id\",state=\"${escape(room.sessionState)}\"} 1")
            }
        }

        family(sb, "xhrec_room_hint", "Why an armed room is not recording, as an info label", "gauge")
        rooms.forEach { (id, room) ->
            room.hintCode?.let { sb.appendLine("xhrec_room_hint{roomId=\"$id\",code=\"${escape(it)}\"} 1") }
        }

        family(sb, "xhrec_room_last_progress_seconds", "Seconds since the session last queued or received data", "gauge")
        rooms.forEach { (id, room) ->
            if (room.lastProgressAtMs > 0) {
                val ageSeconds = (nowMs - room.lastProgressAtMs).coerceAtLeast(0) / 1000.0
                sb.appendLine("xhrec_room_last_progress_seconds{roomId=\"$id\"} $ageSeconds")
            }
        }

        family(sb, "xhrec_room_resume_mark_ahead", "How far the resume mark leads the newest advertised segment id", "gauge")
        rooms.forEach { (id, room) -> sb.appendLine("xhrec_room_resume_mark_ahead{roomId=\"$id\"} ${room.resumeMarkAhead}") }
    }

    private fun family(sb: StringBuilder, name: String, help: String, type: String) {
        sb.appendLine("# HELP $name $help")
        sb.appendLine("# TYPE $name $type")
    }

    /** Label values reach Prometheus verbatim, so quotes and backslashes have to be escaped. */
    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
}
