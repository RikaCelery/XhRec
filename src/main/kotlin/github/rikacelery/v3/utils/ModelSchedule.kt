package github.rikacelery.v3.utils

import github.rikacelery.v3.ml.PredictionEngine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln

/**
 * Model schedule learner that tracks when models go live.
 *
 * Learns the typical broadcast start times for each model based on historical data.
 * Uses hourly granularity and retains more samples for better predictions.
 *
 * Features:
 *   - Per-roomId 24-hour histogram of start times
 *   - Weighted scoring: recent samples have higher weight
 *   - Day-of-week awareness (optional)
 *   - Prediction with confidence score
 *
 * Note: Internally stores by roomId. Use name<->id mapping from RoomComponent for display.
 */
object ModelSchedule {

    /** Days of week for potential future use. */
    enum class DayType {
        WEEKDAY, WEEKEND;

        companion object {
            fun of(dow: java.time.DayOfWeek): DayType =
                if (dow.value <= 5) WEEKDAY else WEEKEND
        }
    }

    data class RoomStats(
        /** 24-hour histogram: count of broadcasts starting at each hour. */
        @Volatile var hourCounts: LongArray = LongArray(24),
        /** Total recorded broadcasts. */
        @Volatile var totalCount: Long = 0,
        /** Last recorded start time (epoch ms). */
        @Volatile var lastStartTime: Long = 0,
        /** Optional: separate weekday/weekend histograms. */
        @Volatile var weekdayCounts: LongArray = LongArray(24),
        @Volatile var weekendCounts: LongArray = LongArray(24),
        /** Recent start times (ring buffer, last 100). */
        @Volatile var recentTimes: LongArray = LongArray(100),
        @Volatile var recentIdx: Int = 0,
        @Volatile var recentCount: Int = 0
    )

    /** Key is roomId (Long) */
    private val rooms = ConcurrentHashMap<Long, RoomStats>()

    /** Maximum age of samples to keep (30 days). */
    private const val MAX_SAMPLE_AGE_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * Record a room going live at the specified time.
     *
     * @param roomId The room's identifier.
     * @param startTime The timestamp when the broadcast started (epoch ms).
     */
    fun record(roomId: Long, startTime: Long) {
        if (startTime <= 0) return

        val zdt = Instant.ofEpochMilli(startTime).atZone(ZoneId.systemDefault())
        val hour = zdt.hour
        val dayType = DayType.of(zdt.dayOfWeek)

        val stat = rooms.computeIfAbsent(roomId) { RoomStats() }
        synchronized(stat) {
            stat.hourCounts[hour]++
            stat.totalCount++
            stat.lastStartTime = startTime

            when (dayType) {
                DayType.WEEKDAY -> stat.weekdayCounts[hour]++
                DayType.WEEKEND -> stat.weekendCounts[hour]++
            }

            // Update ring buffer
            stat.recentTimes[stat.recentIdx % 100] = startTime
            stat.recentIdx++
            stat.recentCount = minOf(stat.recentCount + 1, 100)
        }
        try { PredictionEngine.onRoomWentLive(roomId, startTime) } catch (_: Exception) { }
    }

    /**
     * Get the probability distribution of start times for a room.
     * Returns a 24-element array where each element is the probability (0.0-1.0)
     * of the room starting at that hour.
     *
     * @param roomId The room's identifier.
     * @param dayType If specified, use only weekday/weekend data.
     * @return Probability distribution, or null if no data.
     */
    fun getDistribution(roomId: Long, dayType: DayType? = null): DoubleArray? {
        val stat = rooms[roomId] ?: return null

        synchronized(stat) {
            val counts = when (dayType) {
                DayType.WEEKDAY -> stat.weekdayCounts
                DayType.WEEKEND -> stat.weekendCounts
                null -> stat.hourCounts
            }

            val total = counts.sum()
            if (total == 0L) return null

            return DoubleArray(24) { hour ->
                counts[hour].toDouble() / total
            }
        }
    }

    /**
     * Raw per-room statistics WITHOUT triggering ML prediction.
     * Used by PredictionEngine feature extraction to avoid recursion
     * (snapshot() calls getNextPredictedHour() which consults the ML model).
     */
    data class RawStats(
        val totalCount: Long,
        val lastStartTime: Long
    )

    fun rawStats(roomId: Long): RawStats? {
        val stat = rooms[roomId] ?: return null
        return synchronized(stat) {
            RawStats(totalCount = stat.totalCount, lastStartTime = stat.lastStartTime)
        }
    }

    /**
     * Get the top N most likely start hours for a room.
     *
     * @param roomId The room's identifier.
     * @param n Number of top hours to return.
     * @param dayType If specified, use only weekday/weekend data.
     * @return List of (hour, probability) pairs, sorted by probability descending.
     */
    fun getTopHours(roomId: Long, n: Int = 3, dayType: DayType? = null): List<Pair<Int, Double>> {
        val dist = getDistribution(roomId, dayType) ?: return emptyList()

        return dist.mapIndexed { hour, prob -> hour to prob }
            .sortedByDescending { it.second }
            .take(n)
            .filter { it.second > 0.0 }
    }

    /**
     * Predict if a room is likely to go live soon.
     *
     * @param roomId The room's identifier.
     * @param lookaheadHours How many hours to look ahead.
     * @return Probability of going live within the lookahead window, or null if no data.
     */
    fun predictLiveSoon(roomId: Long, lookaheadHours: Int = 2): Double? {
        try {
            PredictionEngine.predictLiveSoonMl(roomId, lookaheadHours)?.let { return it }
        } catch (_: Exception) { }
        val dist = getDistribution(roomId) ?: return null
        val now = ZonedDateTime.now()
        val currentHour = now.hour

        var probability = 0.0
        for (i in 0 until lookaheadHours) {
            val hour = (currentHour + i) % 24
            probability += dist[hour]
        }

        return probability.coerceAtMost(1.0)
    }

    /**
     * Get the next predicted start hour for a room.
     *
     * @param roomId The room's identifier.
     * @return The hour (0-23) with highest probability that hasn't passed today, or null.
     */
    fun getNextPredictedHour(roomId: Long): Int? {
        try {
            PredictionEngine.nextPredictedHourMl(roomId)?.let { return it }
        } catch (_: Exception) { }
        val dist = getDistribution(roomId) ?: return null
        val currentHour = ZonedDateTime.now().hour

        // Find the hour with highest probability that is >= current hour
        var bestHour: Int? = null
        var bestProb = 0.0

        for (offset in 0 until 24) {
            val hour = (currentHour + offset) % 24
            if (dist[hour] > bestProb) {
                bestProb = dist[hour]
                bestHour = hour
            }
        }

        return if (bestProb > 0.05) bestHour else null // Minimum 5% threshold
    }

    /**
     * Get a snapshot of a room's schedule data for API display.
     */
    data class RoomSnapshot(
        val roomId: Long,
        val totalCount: Long,
        val lastStartTime: Long,
        val hourDistribution: DoubleArray,
        val topHours: List<Pair<Int, Double>>,
        val nextPredictedHour: Int?,
        val recentCount: Int
    )

    fun snapshot(roomId: Long): RoomSnapshot? {
        val stat = rooms[roomId] ?: return null

        synchronized(stat) {
            val dist = getDistribution(roomId) ?: DoubleArray(24)
            val topHours = getTopHours(roomId)
            val nextHour = getNextPredictedHour(roomId)

            return RoomSnapshot(
                roomId = roomId,
                totalCount = stat.totalCount,
                lastStartTime = stat.lastStartTime,
                hourDistribution = dist,
                topHours = topHours,
                nextPredictedHour = nextHour,
                recentCount = stat.recentCount
            )
        }
    }

    /**
     * Get all tracked room IDs.
     */
    fun getAllRoomIds(): Set<Long> = rooms.keys.toSet()

    /**
     * Remove old samples to free memory.
     * Should be called periodically.
     */
    fun cleanup(maxAgeMs: Long = MAX_SAMPLE_AGE_MS) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        // Note: For simplicity, we don't actually remove old histogram data
        // In a production system, you'd want to decay old counts
    }

    fun reset() {
        rooms.clear()
    }

    fun reset(roomId: Long) {
        rooms.remove(roomId)
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    /**
     * Export current schedule state as JSON for persistence.
     */
    fun exportState(): String {
        val roomsJson = buildJsonObject {
            rooms.forEach { (roomId, stat) ->
                synchronized(stat) {
                    put(roomId.toString(), buildJsonObject {
                        put("hourCounts", buildJsonArray { stat.hourCounts.forEach { add(JsonPrimitive(it)) } })
                        put("totalCount", JsonPrimitive(stat.totalCount))
                        put("lastStartTime", JsonPrimitive(stat.lastStartTime))
                        put("weekdayCounts", buildJsonArray { stat.weekdayCounts.forEach { add(JsonPrimitive(it)) } })
                        put("weekendCounts", buildJsonArray { stat.weekendCounts.forEach { add(JsonPrimitive(it)) } })
                        put("recentTimes", buildJsonArray { stat.recentTimes.forEach { add(JsonPrimitive(it)) } })
                        put("recentIdx", JsonPrimitive(stat.recentIdx))
                        put("recentCount", JsonPrimitive(stat.recentCount))
                    })
                }
            }
        }
        return roomsJson.toString()
    }

    /**
     * Import schedule state from JSON produced by [exportState].
     */
    fun importState(json: String) {
        try {
            val root = Json.parseToJsonElement(json).jsonObject
            root.forEach { (roomIdStr, value) ->
                val roomId = roomIdStr.toLongOrNull() ?: return@forEach
                val obj = value.jsonObject
                val stat = RoomStats()
                stat.hourCounts = obj["hourCounts"]?.jsonArray?.map { it.jsonPrimitive.content.toLong() }?.toLongArray()
                    ?: stat.hourCounts
                stat.totalCount = obj["totalCount"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
                stat.lastStartTime = obj["lastStartTime"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
                stat.weekdayCounts = obj["weekdayCounts"]?.jsonArray?.map { it.jsonPrimitive.content.toLong() }?.toLongArray()
                    ?: stat.weekdayCounts
                stat.weekendCounts = obj["weekendCounts"]?.jsonArray?.map { it.jsonPrimitive.content.toLong() }?.toLongArray()
                    ?: stat.weekendCounts
                stat.recentTimes = obj["recentTimes"]?.jsonArray?.map { it.jsonPrimitive.content.toLong() }?.toLongArray()
                    ?: stat.recentTimes
                stat.recentIdx = obj["recentIdx"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                stat.recentCount = obj["recentCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                rooms[roomId] = stat
            }
        } catch (e: Exception) {
            // Keep current in-memory state on import failure
        }
    }


}
