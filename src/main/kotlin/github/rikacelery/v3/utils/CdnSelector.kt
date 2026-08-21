package github.rikacelery.v3.utils

import io.ktor.http.Url
import io.ktor.http.buildUrl
import io.ktor.http.takeFrom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.random.Random

/**
 * Duration-based CDN host selector with multi-granularity temporal learning.
 *
 * Learning dimensions:
 *   - Hour of day (0-23): 24 slots
 *   - Day of week (MON-SUN): 7 days
 *   - Combined: hour × day = 168 slots
 *
 * Hierarchical fallback (when data is sparse):
 *   1. Exact: hour + dayOfWeek
 *   2. Same hour, any day
 *   3. Same 4-hour block + dayOfWeek
 *   4. Same 4-hour block, any day
 *   5. Global EWMA
 *
 * Safety mechanisms:
 *   - Confidence weighting: more samples = higher confidence
 *   - Anomaly detection: sudden performance drops trigger cooldown
 *   - Minimum sample threshold: prevents overfitting to noise
 */
object CdnSelector {

    // ── Time structure ──────────────────────────────────────────────────────

    enum class DaySlot(val label: String) {
        MON("Mon"), TUE("Tue"), WED("Wed"), THU("Thu"),
        FRI("Fri"), SAT("Sat"), SUN("Sun");

        companion object {
            fun of(dow: DayOfWeek): DaySlot = entries[dow.value - 1]
        }
    }

    /** 4-hour coarse block for fallback. */
    enum class BlockSlot(val hourStart: Int, val label: String) {
        B_0_4(0, "00-04"), B_4_8(4, "04-08"), B_8_12(8, "08-12"),
        B_12_16(12, "12-16"), B_16_20(16, "16-20"), B_20_24(20, "20-24");

        companion object {
            fun ofHour(h: Int): BlockSlot = entries[(h.coerceIn(0, 23)) / 4]
        }
    }

    /** Composite key for fine-grained lookup: hour(0-23) + dayOfWeek(0-6). */
    @JvmInline
    value class SlotKey(val value: Int) {
        val hour: Int get() = value / 7
        val dayIdx: Int get() = value % 7
        companion object {
            fun of(hour: Int, day: DaySlot): SlotKey = SlotKey(hour * 7 + day.ordinal)
        }
    }

    // ── Per-host statistics ─────────────────────────────────────────────────

    data class HostStat(
        /** Fine-grained EWMA: 24h × 7days = 168 slots. */
        @Volatile var slotEwma: DoubleArray = DoubleArray(24 * 7) { Double.NaN },
        @Volatile var slotSamples: IntArray = IntArray(24 * 7),

        /** Coarse block EWMA: 6 blocks × 7days = 42 slots. */
        @Volatile var blockEwma: DoubleArray = DoubleArray(6 * 7) { Double.NaN },
        @Volatile var blockSamples: IntArray = IntArray(6 * 7),

        /** Hour-only EWMA (ignoring day): 24 slots. */
        @Volatile var hourEwma: DoubleArray = DoubleArray(24) { Double.NaN },
        @Volatile var hourSamples: IntArray = IntArray(24),

        /** Global EWMA (all time). */
        @Volatile var globalEwma: Double = Double.NaN,
        @Volatile var globalSamples: Int = 0,

        /** Failure tracking. */
        @Volatile var failures: Int = 0,
        @Volatile var totalErrors: Int = 0,
        @Volatile var totalSuccesses: Int = 0,
        @Volatile var cooldownUntil: Long = 0L,
        @Volatile var lastDurationMs: Long = 0L,

        /** Recent samples for anomaly detection (ring buffer, last 20). */
        @Volatile var recentDurations: LongArray = LongArray(20),
        @Volatile var recentIdx: Int = 0,
        @Volatile var recentCount: Int = 0,

        /** Probe (independent measurement) stats — not mixed with main download stats. */
        @Volatile var probeDurationMs: Double = Double.NaN,
        @Volatile var probeSamples: Int = 0,
        @Volatile var probeFailures: Int = 0,
        @Volatile var probeSuccesses: Int = 0,
        @Volatile var probeSuccessful: Boolean = false
    )

    // ── State ───────────────────────────────────────────────────────────────

    private val stats = ConcurrentHashMap<String, HostStat>()
    /** Snapshot of independent probe measurements for one host. */
    data class HostProbeSnapshot(
        val durationMs: Double,
        val samples: Int,
        val failures: Int,
        val successes: Int,
        val lastSuccessful: Boolean
    )



    @Volatile
    var hosts: List<String> = emptyList()

    @Volatile
    var exploreProbability: Double = 0.03

    // ── Constants ───────────────────────────────────────────────────────────

    private const val EWMA_ALPHA = 0.3

    /** Minimum samples before a slot's data is trusted. */
    private const val MIN_SAMPLES_FOR_CONFIDENCE = 3

    /** Anomaly detection: if latest duration > recentAvg * ANOMALY_FACTOR, it's suspicious. */
    private const val ANOMALY_FACTOR = 3.0
    private const val ANOMALY_COOLDOWN_MS = 5_000L

    private const val BASE_COOLDOWN_MS = 2_000L
    private const val MAX_COOLDOWN_MS = 10_000L

    /** Consecutive failures required before a host enters cooldown (tolerates occasional errors). */
    private const val CONSECUTIVE_FAILURE_THRESHOLD = 3

    /** Consecutive anomalies required before anomaly-triggered cooldown. */
    private const val CONSECUTIVE_ANOMALY_THRESHOLD = 2

    // ── Public API ──────────────────────────────────────────────────────────

    fun updateHosts(newHosts: List<String>) {
        val sanitized = newHosts.mapNotNull { it.trim().trimEnd('/').takeIf(String::isNotEmpty) }.distinct()
        stats.keys.retainAll(sanitized.toSet())
        hosts = sanitized
    }

    /**
     * Record a successful download with its duration.
     * Updates all granularity levels (hour+day, block+day, hour-only, global).
     */
    fun record(host: String, durationMs: Long, now: Long = System.currentTimeMillis()) {
        if (durationMs <= 0) return

        val zdt = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        val hour = zdt.hour
        val day = DaySlot.of(zdt.dayOfWeek)
        val block = BlockSlot.ofHour(hour)

        val stat = stats.computeIfAbsent(host) { HostStat() }
        synchronized(stat) {
            // ── Anomaly detection ──
            if (stat.recentCount >= 5) {
                val recentAvg = stat.recentDurations.take(stat.recentCount).average()
                if (durationMs > recentAvg * ANOMALY_FACTOR) {
                    // Sudden spike: increment anomaly streak. Only cooldown when the host
                    // shows a consistent pattern (CONSECUTIVE_ANOMALY_THRESHOLD), so one-off
                    // jitters/slow 404s do NOT penalize the host.
                    stat.failures++
                    if (stat.failures >= CONSECUTIVE_ANOMALY_THRESHOLD) {
                        stat.cooldownUntil = now + ANOMALY_COOLDOWN_MS
                    }
                    return  // Don't update EWMA with anomalous value
                }
            }

            // ── Update recent samples ring buffer ──
            stat.recentDurations[stat.recentIdx % 20] = durationMs
            stat.recentIdx++
            stat.recentCount = minOf(stat.recentCount + 1, 20)

            // ── Success bookkeeping ──
            stat.totalSuccesses++
            stat.failures = 0
            stat.cooldownUntil = 0L
            stat.lastDurationMs = durationMs

            val v = durationMs.toDouble()

            // ── Fine-grained: hour + day ──
            val slotKey = SlotKey.of(hour, day)
            updateEwma(stat.slotEwma, stat.slotSamples, slotKey.value, v)

            // ── Coarse block + day ──
            val blockIdx = block.ordinal * 7 + day.ordinal
            updateEwma(stat.blockEwma, stat.blockSamples, blockIdx, v)

            // ── Hour-only ──
            updateEwma(stat.hourEwma, stat.hourSamples, hour, v)

            // ── Global ──
            if (stat.globalSamples <= 0) {
                stat.globalEwma = v
            } else {
                stat.globalEwma = EWMA_ALPHA * v + (1 - EWMA_ALPHA) * stat.globalEwma
            }
            stat.globalSamples++
        }
    }

    /**
     * Record a failure (transport error, timeout, HTTP 404).
     * Triggers exponential cooldown.
     */
    fun recordFailure(host: String, now: Long = System.currentTimeMillis()) {
        val stat = stats.computeIfAbsent(host) { HostStat() }
        synchronized(stat) {
            stat.failures++
            stat.totalErrors++
            // Only enter cooldown after CONSECUTIVE_FAILURE_THRESHOLD consecutive failures.
            // A few transient 404s/timeouts are tolerated and do NOT penalize the host.
            if (stat.failures >= CONSECUTIVE_FAILURE_THRESHOLD) {
                val excess = stat.failures - CONSECUTIVE_FAILURE_THRESHOLD
                val backoff = (BASE_COOLDOWN_MS * 2.0.pow(excess.coerceAtMost(5))).toLong()
                    .coerceAtMost(MAX_COOLDOWN_MS)
                stat.cooldownUntil = now + backoff
            }
        }
    }
    /**
     * Record a probe result (independent measurement, not a main download).
     * Probes keep other hosts' stats fresh without consuming main-download traffic.
     */
    fun probe(host: String, durationMs: Long, now: Long = System.currentTimeMillis()) {
        val stat = stats.computeIfAbsent(host) { HostStat() }
        synchronized(stat) {
            if (durationMs <= 0) {
                stat.probeFailures++
                stat.probeSuccessful = false
                return
            }
            stat.probeSuccesses++
            stat.probeFailures = 0
            val v = durationMs.toDouble()
            stat.probeDurationMs = if (stat.probeSamples <= 0 || stat.probeDurationMs.isNaN()) v
            else EWMA_ALPHA * v + (1 - EWMA_ALPHA) * stat.probeDurationMs
            stat.probeSamples++
            stat.probeSuccessful = true
        }
    }

    /** Record a probe failure (e.g. 404 / timeout during probe). */
    fun probeFailure(host: String, now: Long = System.currentTimeMillis()) {
        val stat = stats.computeIfAbsent(host) { HostStat() }
        synchronized(stat) {
            stat.probeFailures++
            stat.probeSuccessful = false
        }
    }

    /** Snapshot of probe stats for a host (for API/UI). */
    fun probeSnapshot(host: String): HostProbeSnapshot? {
        val stat = stats[host] ?: return null
        return synchronized(stat) {
            HostProbeSnapshot(
                durationMs = stat.probeDurationMs,
                samples = stat.probeSamples,
                failures = stat.probeFailures,
                successes = stat.probeSuccesses,
                lastSuccessful = stat.probeSuccessful
            )
        }
    }


    fun select(now: Long = System.currentTimeMillis()): String {
        val avail = availableHosts(now)
        if (avail.isEmpty()) return hosts.firstOrNull() ?: ""
        if (avail.size == 1) return avail[0]

        val best = bestAvailable(avail, now)
        if (best == null) {
            return avail[Random.nextInt(avail.size)]
        }
        if (Random.nextDouble() < exploreProbability) {
            val others = avail.filter { it != best }
            if (others.isNotEmpty()) return others[Random.nextInt(others.size)]
        }
        return best
    }

    fun resolve(url: String, now: Long = System.currentTimeMillis()): String {
        val host = select(now)
        if (host.isEmpty()) return url
        return rewriteHost(url, host)
    }

    fun rewriteHost(url: String, host: String): String {
        return try {
            val u = Url(url)
            if (u.host.isEmpty()) return url
            buildUrl {
                takeFrom(u)
                this.host = host
            }.toString()
        } catch (e: Exception) { url }
    }

    fun hostOf(url: String): String = try { Url(url).host } catch (e: Exception) { "" }

    // ── Snapshot for API/Metrics ────────────────────────────────────────────

    data class HostStatSnapshot(
        /** Best estimated duration at current time (ms). */
        val estimatedDurationMs: Double,
        /** Which level of the hierarchy provided the estimate. */
        val estimateSource: String,
        /** Confidence: 0.0 (no data) to 1.0 (fully confident). */
        val confidence: Double,
        val globalEwma: Double,
        val globalSamples: Int,
        val failures: Int,
        val totalErrors: Int,
        val totalSuccesses: Int,
        val cooldownUntil: Long,
        val lastDurationMs: Long,
        /** Per-hour EWMA (ignoring day) for visualization. */
        val hourEwma: DoubleArray,
        val hourSamples: IntArray
    )

    /**
     * Snapshot with custom timestamp (for testing or historical analysis).
     */
    fun snapshot(now: Long): Map<String, HostStatSnapshot> {
        val zdt = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        val hour = zdt.hour
        val day = DaySlot.of(zdt.dayOfWeek)
        val block = BlockSlot.ofHour(hour)

        return stats.entries.associate { (host, stat) ->
            host to synchronized(stat) {
                val (estimate, source) = hierarchicalEstimate(stat, hour, day, block)
                val confidence = computeConfidence(stat, hour, day, block)

                HostStatSnapshot(
                    estimatedDurationMs = estimate,
                    estimateSource = source,
                    confidence = confidence,
                    globalEwma = if (stat.globalSamples > 0) stat.globalEwma else Double.NaN,
                    globalSamples = stat.globalSamples,
                    failures = stat.failures,
                    totalErrors = stat.totalErrors,
                    totalSuccesses = stat.totalSuccesses,
                    cooldownUntil = stat.cooldownUntil,
                    lastDurationMs = stat.lastDurationMs,
                    hourEwma = stat.hourEwma.copyOf(),
                    hourSamples = stat.hourSamples.copyOf()
                )
            }
        }
    }

    /**
     * Snapshot with current system time.
     */
    fun snapshot(): Map<String, HostStatSnapshot> = snapshot(System.currentTimeMillis())

    fun reset() {
        stats.clear()
        hosts = emptyList()
        exploreProbability = 0.03
    }
    /**
     * Clear cooling state for a specific host (or all hosts if host is empty),
     * resetting failures and cooldown so it can be selected immediately.
     */
    fun clearCooldown(host: String = "") {
        if (host.isEmpty()) {
            stats.forEach { it.value.run {
                failures = 0
                cooldownUntil = 0L
            } }
        } else {
            stats[host]?.run {
                failures = 0
                cooldownUntil = 0L
            }
        }
    }


    // ── Persistence ─────────────────────────────────────────────────────────

    /**
     * Export current state as JSON for persistence.
     * Format: { "exploreProbability": X, "hosts": [...], "stats": { host: {...} } }
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun exportState(): String {
        val statsJson = buildJsonObject {
            stats.forEach { (host, stat) ->
                synchronized(stat) {
                    put(host, buildJsonObject {
                        put("slotEwma", buildJsonArray { stat.slotEwma.forEach { add(JsonPrimitive(if (it.isNaN()) -1.0 else it)) } })
                        put("slotSamples", buildJsonArray { stat.slotSamples.forEach { add(JsonPrimitive(it)) } })
                        put("blockEwma", buildJsonArray { stat.blockEwma.forEach { add(JsonPrimitive(if (it.isNaN()) -1.0 else it)) } })
                        put("blockSamples", buildJsonArray { stat.blockSamples.forEach { add(JsonPrimitive(it)) } })
                        put("hourEwma", buildJsonArray { stat.hourEwma.forEach { add(JsonPrimitive(if (it.isNaN()) -1.0 else it)) } })
                        put("hourSamples", buildJsonArray { stat.hourSamples.forEach { add(JsonPrimitive(it)) } })
                        put("globalEwma", JsonPrimitive(if (stat.globalEwma.isNaN()) -1.0 else stat.globalEwma))
                        put("globalSamples", JsonPrimitive(stat.globalSamples))
                        put("failures", JsonPrimitive(stat.failures))
                        put("totalErrors", JsonPrimitive(stat.totalErrors))
                        put("totalSuccesses", JsonPrimitive(stat.totalSuccesses))
                        put("cooldownUntil", JsonPrimitive(stat.cooldownUntil))
                        put("lastDurationMs", JsonPrimitive(stat.lastDurationMs))
                        put("recentDurations", buildJsonArray { stat.recentDurations.forEach { add(JsonPrimitive(it)) } })
                        put("recentIdx", JsonPrimitive(stat.recentIdx))
                        put("recentCount", JsonPrimitive(stat.recentCount))
                        put("probeDurationMs", JsonPrimitive(if (stat.probeDurationMs.isNaN()) -1.0 else stat.probeDurationMs))
                        put("probeSamples", JsonPrimitive(stat.probeSamples))
                        put("probeFailures", JsonPrimitive(stat.probeFailures))
                        put("probeSuccesses", JsonPrimitive(stat.probeSuccesses))
                    })
                }
            }
        }
        val json = buildJsonObject {
            put("exploreProbability", JsonPrimitive(exploreProbability))
            put("hosts", buildJsonArray { hosts.forEach { add(JsonPrimitive(it)) } })
            put("stats", statsJson)
        }
        return json.toString()
    }

    /**
     * Import state from JSON produced by [exportState].
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun importState(json: String) {
        try {
            val root = Json.parseToJsonElement(json).jsonObject
            val newHosts = root["hosts"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val newStats = ConcurrentHashMap<String, HostStat>()

            val statsObj = root["stats"]?.jsonObject
            if (statsObj != null) {
                statsObj.forEach { (host, value) ->
                    val obj = value.jsonObject
                    val stat = HostStat()
                    stat.slotEwma = obj["slotEwma"]?.jsonArray?.map { (it.jsonPrimitive.content.toDoubleOrNull() ?: -1.0).let { d -> if (d < 0) Double.NaN else d } }?.toDoubleArray()
                        ?: stat.slotEwma
                    stat.slotSamples = obj["slotSamples"]?.jsonArray?.map { it.jsonPrimitive.content.toInt() }?.toIntArray()
                        ?: stat.slotSamples
                    stat.blockEwma = obj["blockEwma"]?.jsonArray?.map { (it.jsonPrimitive.content.toDoubleOrNull() ?: -1.0).let { d -> if (d < 0) Double.NaN else d } }?.toDoubleArray()
                        ?: stat.blockEwma
                    stat.blockSamples = obj["blockSamples"]?.jsonArray?.map { it.jsonPrimitive.content.toInt() }?.toIntArray()
                        ?: stat.blockSamples
                    stat.hourEwma = obj["hourEwma"]?.jsonArray?.map { (it.jsonPrimitive.content.toDoubleOrNull() ?: -1.0).let { d -> if (d < 0) Double.NaN else d } }?.toDoubleArray()
                        ?: stat.hourEwma
                    stat.hourSamples = obj["hourSamples"]?.jsonArray?.map { it.jsonPrimitive.content.toInt() }?.toIntArray()
                        ?: stat.hourSamples
                    stat.globalEwma = obj["globalEwma"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: stat.globalEwma
                    stat.globalSamples = obj["globalSamples"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    stat.failures = obj["failures"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    stat.totalErrors = obj["totalErrors"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    stat.totalSuccesses = obj["totalSuccesses"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    stat.cooldownUntil = obj["cooldownUntil"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                    stat.lastDurationMs = obj["lastDurationMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                    stat.recentDurations = obj["recentDurations"]?.jsonArray?.map { it.jsonPrimitive.content.toLong() }?.toLongArray()
                        ?: stat.recentDurations
                    stat.recentIdx = obj["recentIdx"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    stat.recentCount = obj["recentCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    stat.probeDurationMs = obj["probeDurationMs"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: stat.probeDurationMs
                    stat.probeSamples = obj["probeSamples"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    stat.probeFailures = obj["probeFailures"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    stat.probeSuccesses = obj["probeSuccesses"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    newStats[host] = stat
                }
            }

            importHosts(newHosts)
            newStats.forEach { (k, v) -> stats[k] = v }
            exploreProbability = root["exploreProbability"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: exploreProbability
        } catch (e: Exception) {
            // If import fails, keep current in-memory state
        }
    }

    private fun importHosts(newHosts: List<String>) {
        stats.keys.retainAll(newHosts.toSet())
        hosts = newHosts
    }



    // ── Internal helpers ────────────────────────────────────────────────────

    private fun availableHosts(now: Long): List<String> =
        hosts.filter { host -> (stats[host]?.cooldownUntil ?: 0L) <= now }

    /**
     * Hierarchical fallback estimation.
     * Returns (durationMs, sourceLabel).
     */
    private fun hierarchicalEstimate(
        stat: HostStat, hour: Int, day: DaySlot, block: BlockSlot
    ): Pair<Double, String> {
        // Level 1: hour + day (finest)
        val slotKey = SlotKey.of(hour, day)
        if (stat.slotSamples[slotKey.value] >= MIN_SAMPLES_FOR_CONFIDENCE) {
            return stat.slotEwma[slotKey.value] to "hour+day"
        }

        // Level 2: hour only (ignore day)
        if (stat.hourSamples[hour] >= MIN_SAMPLES_FOR_CONFIDENCE) {
            return stat.hourEwma[hour] to "hour"
        }

        // Level 3: block + day
        val blockIdx = block.ordinal * 7 + day.ordinal
        if (stat.blockSamples[blockIdx] >= MIN_SAMPLES_FOR_CONFIDENCE) {
            return stat.blockEwma[blockIdx] to "block+day"
        }

        // Level 4: block only (ignore day)
        val blockOnlySamples = (0 until 7).sumOf { stat.blockSamples[block.ordinal * 7 + it] }
        if (blockOnlySamples >= MIN_SAMPLES_FOR_CONFIDENCE) {
            var weighted = 0.0
            var totalWeight = 0.0
            for (d in 0 until 7) {
                val idx = block.ordinal * 7 + d
                val s = stat.blockSamples[idx]
                if (s > 0) {
                    weighted += s * stat.blockEwma[idx]
                    totalWeight += s
                }
            }
            if (totalWeight > 0) return weighted / totalWeight to "block"
        }

        // Level 5: global fallback
        if (stat.globalSamples > 0) {
            return stat.globalEwma to "global"
        }

        return Double.NaN to "none"
    }

    /**
     * Compute confidence score (0.0 to 1.0) based on data availability.
     * More samples at finer granularity = higher confidence.
     */
    private fun computeConfidence(
        stat: HostStat, hour: Int, day: DaySlot, block: BlockSlot
    ): Double {
        val slotKey = SlotKey.of(hour, day)
        val slotS = stat.slotSamples[slotKey.value]

        // Sigmoid-like confidence: saturates around 10-15 samples
        return when {
            slotS >= 15 -> 0.95
            slotS >= 10 -> 0.85
            slotS >= 5 -> 0.70
            stat.hourSamples[hour] >= 10 -> 0.60
            stat.hourSamples[hour] >= 5 -> 0.50
            stat.globalSamples >= 10 -> 0.40
            stat.globalSamples >= 5 -> 0.30
            stat.globalSamples > 0 -> 0.15
            else -> 0.0
        }
    }

    private fun bestAvailable(avail: List<String>, now: Long): String? {
        val zdt = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        val hour = zdt.hour
        val day = DaySlot.of(zdt.dayOfWeek)
        val block = BlockSlot.ofHour(hour)

        var best: String? = null
        var bestScore = Double.MAX_VALUE

        for (host in avail) {
            val stat = stats[host] ?: continue
            val (duration, _) = synchronized(stat) { hierarchicalEstimate(stat, hour, day, block) }
            if (duration.isNaN()) continue

            // Blend main-download estimate with independent probe estimate (if available):
            // probes keep host scores fresh without diluting main-download statistics.
            val score = if (!stat.probeDurationMs.isNaN() && stat.probeSamples >= MIN_SAMPLES_FOR_CONFIDENCE) {
                duration * 0.8 + stat.probeDurationMs * 0.2
            } else {
                duration
            }
            if (score < bestScore) {
                bestScore = score
                best = host
            }
        }
        return best
    }

    private fun updateEwma(ewma: DoubleArray, samples: IntArray, idx: Int, value: Double) {
        if (samples[idx] <= 0) {
            ewma[idx] = value
        } else {
            ewma[idx] = EWMA_ALPHA * value + (1 - EWMA_ALPHA) * ewma[idx]
        }
        samples[idx]++
    }
}
