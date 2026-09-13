package github.rikacelery.v3.utils

import github.rikacelery.v3.data.RoomStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Ten-minute occupancy history per room: the training data for the open/closed forecast, and the
 * source of the "historical show records" grid.
 *
 * The platform only tells us a room's status as it changes, so the old learner recorded *when a
 * room went live* and nothing else — it could not see how long a show lasted, and a room that was
 * live for six hours looked the same as one that flickered on for a minute. Sampling every room on
 * a ten-minute grid captures the duration, which is what makes "this room is usually open at 22:00
 * on a Friday" answerable.
 *
 * A day is [SLOTS_PER_DAY] characters, one per slot:
 *   `.` never sampled (the app was down) — *not* the same as closed, so it is excluded from the
 *   statistics rather than counted as evidence against a show;
 *   `0` sampled and closed;
 *   `1` public, `2` ticket/group show, `3` private, `4` p2p — open, with the kind of show kept only
 *   so the grid can colour it.
 *
 * Only days that were sampled at all are kept, and days older than [KEEP_DAYS] are dropped.
 */
object ScheduleHistory {

    const val SLOTS_PER_DAY = 144
    const val SLOT_MINUTES = 10
    const val KEEP_DAYS = 90L

    const val UNKNOWN = '.'
    const val CLOSED = '0'
    const val CODE_PUBLIC = '1'
    const val CODE_TICKET = '2'
    const val CODE_PRIVATE = '3'
    const val CODE_P2P = '4'

    private val zone: ZoneId = ZoneId.systemDefault()
    private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** roomId -> (yyyy-MM-dd -> one char per ten-minute slot) */
    private val rooms = ConcurrentHashMap<Long, ConcurrentHashMap<String, CharArray>>()

    /** One day of the grid. */
    data class Day(val date: String, val slots: String)

    /** One predicted day: a probability per slot, 0..1. */
    data class Forecast(val date: String, val slots: DoubleArray) {
        override fun equals(other: Any?): Boolean =
            other is Forecast && other.date == date && other.slots.contentEquals(slots)

        override fun hashCode(): Int = 31 * date.hashCode() + slots.contentHashCode()
    }

    /**
     * The status a slot is filed under.
     *
     * p2p is checked before [RoomStatus.isOffline] on purpose: that helper counts p2p as offline
     * because the recorder cannot capture a WebRTC show, but for "did this room have a show" it is
     * open — it is a paid show the platform is running, and the grid colours it like one.
     */
    fun code(status: String): Char = when {
        RoomStatus.isRtc(status) -> CODE_P2P
        RoomStatus.isOffline(status) -> CLOSED
        RoomStatus.isPublic(status) -> CODE_PUBLIC
        RoomStatus.isGroupShow(status) -> CODE_TICKET
        else -> CODE_PRIVATE
    }

    /** The user's rule: everything that is not closed counts as open, whatever kind of show it is. */
    fun isOpen(code: Char): Boolean = code != CLOSED && code != UNKNOWN

    fun slotOf(now: Long): Int {
        val zdt = Instant.ofEpochMilli(now).atZone(zone)
        return zdt.hour * (60 / SLOT_MINUTES) + zdt.minute / SLOT_MINUTES
    }

    fun dayOf(now: Long): String = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().format(dayFormat)

    /**
     * Files the status of every room into the current slot. Returns true when something changed, so
     * the caller can decide whether the history is worth persisting.
     */
    fun sample(statuses: Map<Long, String>, now: Long = System.currentTimeMillis()): Boolean {
        val day = dayOf(now)
        val slot = slotOf(now)
        var changed = false
        statuses.forEach { (roomId, status) ->
            if (status.isEmpty()) return@forEach
            val codes = rooms.computeIfAbsent(roomId) { ConcurrentHashMap() }
                .computeIfAbsent(day) { CharArray(SLOTS_PER_DAY) { UNKNOWN } }
            val code = code(status)
            synchronized(codes) {
                if (codes[slot] != code) {
                    codes[slot] = code
                    changed = true
                }
            }
        }
        return changed
    }

    /**
     * Aggregates a room's history into the probability of a slot for each weekday.
     *
     * A plain frequency per (weekday, slot) cell is far too sparse to be useful — thirteen weeks of
     * data is thirteen observations of any given cell — so the estimate is smoothed twice: the cell
     * leans on the same slot across all weekdays, which in turn leans on the room's overall rate.
     * That is the whole model; it needs no training pass and it stays explainable.
     */
    fun forecast(roomId: Long, days: Int = 3, now: Long = System.currentTimeMillis()): List<Forecast> {
        val byDay = rooms[roomId] ?: return emptyList()
        val cellOpen = Array(7) { IntArray(SLOTS_PER_DAY) }
        val cellSeen = Array(7) { IntArray(SLOTS_PER_DAY) }
        val slotOpen = IntArray(SLOTS_PER_DAY)
        val slotSeen = IntArray(SLOTS_PER_DAY)
        var totalOpen = 0
        var totalSeen = 0
        byDay.forEach { (date, codes) ->
            val dow = runCatching { LocalDate.parse(date, dayFormat).dayOfWeek.value - 1 }.getOrNull()
                ?: return@forEach
            synchronized(codes) {
                for (slot in 0 until SLOTS_PER_DAY) {
                    val code = codes[slot]
                    if (code == UNKNOWN) continue
                    val open = if (isOpen(code)) 1 else 0
                    cellSeen[dow][slot]++
                    slotSeen[slot]++
                    totalSeen++
                    if (open == 1) {
                        cellOpen[dow][slot]++
                        slotOpen[slot]++
                        totalOpen++
                    }
                }
            }
        }
        if (totalSeen == 0) return emptyList()
        val alpha = 2.0
        val overall = totalOpen.toDouble() / totalSeen
        fun probability(dow: Int, slot: Int): Double {
            val slotRate = (slotOpen[slot] + alpha * overall) / (slotSeen[slot] + alpha)
            return (cellOpen[dow][slot] + alpha * slotRate) / (cellSeen[dow][slot] + alpha)
        }
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (1..days).map { offset ->
            val date = today.plusDays(offset.toLong())
            val dow = date.dayOfWeek.value - 1
            Forecast(date.format(dayFormat), DoubleArray(SLOTS_PER_DAY) { slot -> probability(dow, slot) })
        }
    }

    /** The grid's history rows, newest first, covering at most [days] days. */
    fun history(roomId: Long, days: Int = 7, now: Long = System.currentTimeMillis()): List<Day> {
        val byDay = rooms[roomId] ?: return emptyList()
        val cutoff = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(days - 1L).format(dayFormat)
        return byDay.entries
            .filter { it.key >= cutoff }
            .sortedByDescending { it.key }
            .map { (date, codes) -> Day(date, synchronized(codes) { String(codes) }) }
    }

    fun rooms(): Set<Long> = rooms.keys.toSet()

    /** Drops days past the retention window, and rooms left with no days at all. */
    fun cleanup(now: Long = System.currentTimeMillis()) {
        val cutoff = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(KEEP_DAYS).format(dayFormat)
        rooms.entries.removeIf { (_, byDay) ->
            byDay.keys.removeIf { it < cutoff }
            byDay.isEmpty()
        }
    }

    fun reset() = rooms.clear()

    fun reset(roomId: Long) = rooms.remove(roomId)

    // ── Persistence ─────────────────────────────────────────────────────────

    fun exportState(): String = buildJsonObject {
        rooms.forEach { (roomId, byDay) ->
            put(roomId.toString(), buildJsonObject {
                byDay.forEach { (date, codes) -> put(date, JsonPrimitive(synchronized(codes) { String(codes) })) }
            })
        }
    }.toString()

    fun importState(json: String) {
        try {
            Json.parseToJsonElement(json).jsonObject.forEach { (roomIdText, daysValue) ->
                val roomId = roomIdText.toLongOrNull() ?: return@forEach
                val byDay = ConcurrentHashMap<String, CharArray>()
                daysValue.jsonObject.forEach { (date, slotsValue) ->
                    val text = slotsValue.jsonPrimitive.content
                    if (text.length != SLOTS_PER_DAY) return@forEach
                    byDay[date] = text.toCharArray()
                }
                if (byDay.isNotEmpty()) rooms[roomId] = byDay
            }
        } catch (_: Exception) {
            // Keep whatever is already in memory; a corrupt history must not stop the app.
        }
    }
}
