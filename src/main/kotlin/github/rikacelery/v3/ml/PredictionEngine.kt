package github.rikacelery.v3.ml

import github.rikacelery.v3.utils.CdnSelector
import github.rikacelery.v3.utils.ModelSchedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference

/**
 * In-process LightGBM-style prediction engine for:
 *  - CDN duration regression (pick fastest host)
 *  - Model go-live binary classification (per hour slot)
 *
 * Samples + models are written relative to the process working directory
 * (Docker WORKDIR is /config, so files land on the config mount automatically).
 * Cold-start: returns null so callers keep EWMA / histogram heuristics.
 */
object PredictionEngine {
    private val logger = LoggerFactory.getLogger(PredictionEngine::class.java)

    private const val MIN_CDN_SAMPLES = 40
    private const val MIN_SCHEDULE_SAMPLES = 30
    private const val MIN_CDN_HOSTS = 2

    private val cdnModel = AtomicReference<Gbdt.Model?>(null)
    private val scheduleModel = AtomicReference<Gbdt.Model?>(null)

    @Volatile private var trainJob: Job? = null
    @Volatile private var dataDir: File = File("/config")

    private val cdnFeatureNames = listOf(
        "hour", "dow", "is_weekend", "host_hash",
        "recent_avg", "recent_std", "failures", "probe_ms",
        "global_ewma", "hour_ewma", "success_rate"
    )

    private val scheduleFeatureNames = listOf(
        "hour", "dow", "is_weekend", "room_hash",
        "hour_hist", "weekday_hist", "weekend_hist",
        "hours_since_last", "total_count_log"
    )

    fun start(scope: CoroutineScope, dir: File = File("."), trainIntervalMs: Long = 30 * 60_000L) {
        dataDir = dir
        dataDir.mkdirs()
        PredictionSampleStore.configure(dataDir)
        loadModels()
        trainJob?.cancel()
        trainJob = scope.launch {
            // initial train shortly after boot if enough samples
            delay(15_000)
            while (isActive) {
                try {
                    withContext(Dispatchers.IO) { trainAll() }
                } catch (e: Exception) {
                    logger.warn("ML train failed: {}", e.message)
                }
                delay(trainIntervalMs)
            }
        }
        logger.info("PredictionEngine started (dataDir={}, trainEvery={}ms)", dataDir.absolutePath, trainIntervalMs)
    }

    fun stop() {
        trainJob?.cancel()
        trainJob = null
        PredictionSampleStore.flush()
        // models already on disk after train; flush samples
    }

    /** Always cwd (Docker WORKDIR=/config). Kept for callers. */
    fun resolveDataDir(): File = File(".")

    /** Test / manual reset of in-memory models (does not delete sample files). */
    fun resetModels() {
        cdnModel.set(null)
        scheduleModel.set(null)
    }

    // ── Sample collection ───────────────────────────────────────────────────

    fun onCdnSuccess(host: String, durationMs: Long, now: Long = System.currentTimeMillis()) {
        val feats = cdnRawFeatures(host, now) ?: return
        PredictionSampleStore.recordCdn(
            PredictionSampleStore.CdnSample(
                ts = now,
                host = host,
                durationMs = durationMs.toDouble(),
                hour = feats.hour,
                dow = feats.dow,
                isWeekend = feats.isWeekend,
                recentAvg = feats.recentAvg,
                recentStd = feats.recentStd,
                failures = feats.failures,
                probeMs = feats.probeMs,
                success = 1
            )
        )
    }

    fun onCdnFailure(host: String, now: Long = System.currentTimeMillis()) {
        val feats = cdnRawFeatures(host, now) ?: return
        // Treat hard failure as a large duration penalty sample for regression
        PredictionSampleStore.recordCdn(
            PredictionSampleStore.CdnSample(
                ts = now,
                host = host,
                durationMs = (feats.recentAvg.takeIf { it > 0 } ?: 5000.0) * 3.0,
                hour = feats.hour,
                dow = feats.dow,
                isWeekend = feats.isWeekend,
                recentAvg = feats.recentAvg,
                recentStd = feats.recentStd,
                failures = feats.failures + 1,
                probeMs = feats.probeMs,
                success = 0
            )
        )
    }

    fun onRoomWentLive(roomId: Long, startTime: Long = System.currentTimeMillis()) {
        val zdt = Instant.ofEpochMilli(startTime).atZone(ZoneId.systemDefault())
        val hour = zdt.hour
        val dow = zdt.dayOfWeek.value - 1
        val isWeekend = if (zdt.dayOfWeek.value >= 6) 1 else 0
        val raw = ModelSchedule.rawStats(roomId)
        val hourHist = ModelSchedule.getDistribution(roomId)?.getOrNull(hour) ?: 0.0
        val weekdayHist = ModelSchedule.getDistribution(roomId, ModelSchedule.DayType.WEEKDAY)?.getOrNull(hour) ?: 0.0
        val weekendHist = ModelSchedule.getDistribution(roomId, ModelSchedule.DayType.WEEKEND)?.getOrNull(hour) ?: 0.0
        val hoursSince = if (raw != null && raw.lastStartTime > 0 && raw.lastStartTime != startTime) {
            ((startTime - raw.lastStartTime).toDouble() / 3_600_000.0).coerceIn(0.0, 720.0)
        } else 48.0
        val total = raw?.totalCount?.toInt() ?: 0

        // Positive at live hour
        PredictionSampleStore.recordSchedule(
            PredictionSampleStore.ScheduleSample(
                ts = startTime, roomId = roomId, hour = hour, dow = dow, isWeekend = isWeekend,
                hourHist = hourHist, weekdayHist = weekdayHist, weekendHist = weekendHist,
                hoursSinceLast = hoursSince, totalCount = total, wentLive = 1
            )
        )
        // Weak negatives: two other hours same day (helps binary model)
        val negHours = listOf((hour + 6) % 24, (hour + 12) % 24)
        for (nh in negHours) {
            PredictionSampleStore.recordSchedule(
                PredictionSampleStore.ScheduleSample(
                    ts = startTime, roomId = roomId, hour = nh, dow = dow, isWeekend = isWeekend,
                    hourHist = ModelSchedule.getDistribution(roomId)?.getOrNull(nh) ?: 0.0,
                    weekdayHist = ModelSchedule.getDistribution(roomId, ModelSchedule.DayType.WEEKDAY)?.getOrNull(nh) ?: 0.0,
                    weekendHist = ModelSchedule.getDistribution(roomId, ModelSchedule.DayType.WEEKEND)?.getOrNull(nh) ?: 0.0,
                    hoursSinceLast = hoursSince, totalCount = total, wentLive = 0
                )
            )
        }
    }

    // ── Inference ───────────────────────────────────────────────────────────

    /**
     * Predict download duration (ms) for [host] at [now]. Null if model not ready.
     */
    fun predictCdnDurationMs(host: String, now: Long = System.currentTimeMillis()): Double? {
        val model = cdnModel.get() ?: return null
        val vec = cdnFeatureVector(host, now) ?: return null
        val p = Gbdt(task = Gbdt.Task.REGRESSION).predict(model, vec)
        return p.coerceAtLeast(1.0)
    }

    /**
     * Pick host with lowest predicted duration among [hosts]. Null if model not ready.
     */
    fun selectBestCdn(hosts: List<String>, now: Long = System.currentTimeMillis()): String? {
        if (cdnModel.get() == null || hosts.size < 2) return null
        var best: String? = null
        var bestScore = Double.MAX_VALUE
        for (h in hosts) {
            val d = predictCdnDurationMs(h, now) ?: return null
            if (d < bestScore) {
                bestScore = d
                best = h
            }
        }
        return best
    }

    /**
     * Probability that [roomId] goes live at [hour] (0-23). Null if model not ready.
     */
    fun predictLiveProbability(roomId: Long, hour: Int, now: Long = System.currentTimeMillis()): Double? {
        val model = scheduleModel.get() ?: return null
        val vec = scheduleFeatureVector(roomId, hour, now) ?: return null
        return Gbdt(task = Gbdt.Task.BINARY).predict(model, vec).coerceIn(0.0, 1.0)
    }

    fun predictLiveSoonMl(roomId: Long, lookaheadHours: Int = 2, now: Long = System.currentTimeMillis()): Double? {
        if (scheduleModel.get() == null) return null
        val zdt = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        var p = 0.0
        var any = false
        for (i in 0 until lookaheadHours) {
            val h = (zdt.hour + i) % 24
            val ph = predictLiveProbability(roomId, h, now) ?: return null
            p += ph
            any = true
        }
        return if (any) p.coerceAtMost(1.0) else null
    }

    fun nextPredictedHourMl(roomId: Long, now: Long = System.currentTimeMillis()): Int? {
        if (scheduleModel.get() == null) return null
        val zdt = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        var bestHour: Int? = null
        var best = 0.0
        for (offset in 0 until 24) {
            val h = (zdt.hour + offset) % 24
            val p = predictLiveProbability(roomId, h, now) ?: return null
            if (p > best) {
                best = p
                bestHour = h
            }
        }
        return if (best > 0.08) bestHour else null
    }

    data class Status(
        val dataDir: String,
        val cdnSamples: Int,
        val scheduleSamples: Int,
        val cdnModelReady: Boolean,
        val scheduleModelReady: Boolean,
        val cdnTrees: Int,
        val scheduleTrees: Int
    )

    fun status(): Status = Status(
        dataDir = PredictionSampleStore.dataDir.absolutePath,
        cdnSamples = PredictionSampleStore.cdnCount(),
        scheduleSamples = PredictionSampleStore.scheduleCount(),
        cdnModelReady = cdnModel.get() != null,
        scheduleModelReady = scheduleModel.get() != null,
        cdnTrees = cdnModel.get()?.trees?.size ?: 0,
        scheduleTrees = scheduleModel.get()?.trees?.size ?: 0
    )

    // ── Training ────────────────────────────────────────────────────────────

    fun trainAll() {
        trainCdn()
        trainSchedule()
        PredictionSampleStore.flush()
    }

    fun trainCdn() {
        val samples = PredictionSampleStore.cdnSnapshot().filter { it.durationMs > 0 }
        if (samples.size < MIN_CDN_SAMPLES) {
            logger.debug("CDN train skip: {} < {} samples", samples.size, MIN_CDN_SAMPLES)
            return
        }
        val hosts = samples.map { it.host }.distinct()
        if (hosts.size < MIN_CDN_HOSTS) {
            logger.debug("CDN train skip: only {} hosts", hosts.size)
            return
        }
        val x = Array(samples.size) { i ->
            val s = samples[i]
            doubleArrayOf(
                s.hour.toDouble(),
                s.dow.toDouble(),
                s.isWeekend.toDouble(),
                hostHash(s.host),
                s.recentAvg,
                s.recentStd,
                s.failures.toDouble(),
                if (s.probeMs.isNaN()) -1.0 else s.probeMs,
                s.recentAvg, // proxy global
                s.recentAvg, // proxy hour ewma at sample time
                s.success.toDouble()
            )
        }
        val y = DoubleArray(samples.size) { samples[it].durationMs }
        val model = Gbdt(
            task = Gbdt.Task.REGRESSION,
            nTrees = 36,
            maxDepth = 4,
            learningRate = 0.08,
            minLeaf = 6
        ).train(x, y, cdnFeatureNames)
        cdnModel.set(model)
        Gbdt.save(model, File(PredictionSampleStore.dataDir, "ml-cdn-model.json"))
        logger.info("CDN GBDT trained: samples={}, hosts={}, trees={}", samples.size, hosts.size, model.trees.size)
    }

    fun trainSchedule() {
        val samples = PredictionSampleStore.scheduleSnapshot()
        if (samples.size < MIN_SCHEDULE_SAMPLES) {
            logger.debug("Schedule train skip: {} < {} samples", samples.size, MIN_SCHEDULE_SAMPLES)
            return
        }
        val pos = samples.count { it.wentLive == 1 }
        if (pos < 10) {
            logger.debug("Schedule train skip: only {} positives", pos)
            return
        }
        val x = Array(samples.size) { i ->
            val s = samples[i]
            doubleArrayOf(
                s.hour.toDouble(),
                s.dow.toDouble(),
                s.isWeekend.toDouble(),
                roomHash(s.roomId),
                s.hourHist,
                s.weekdayHist,
                s.weekendHist,
                s.hoursSinceLast,
                kotlin.math.ln((s.totalCount + 1).toDouble())
            )
        }
        val y = DoubleArray(samples.size) { samples[it].wentLive.toDouble() }
        val model = Gbdt(
            task = Gbdt.Task.BINARY,
            nTrees = 40,
            maxDepth = 4,
            learningRate = 0.08,
            minLeaf = 6
        ).train(x, y, scheduleFeatureNames)
        scheduleModel.set(model)
        Gbdt.save(model, File(PredictionSampleStore.dataDir, "ml-schedule-model.json"))
        logger.info("Schedule GBDT trained: samples={}, pos={}, trees={}", samples.size, pos, model.trees.size)
    }

    private fun loadModels() {
        Gbdt.load(File(PredictionSampleStore.dataDir, "ml-cdn-model.json"))?.let {
            cdnModel.set(it)
            logger.info("Loaded CDN model ({} trees)", it.trees.size)
        }
        Gbdt.load(File(PredictionSampleStore.dataDir, "ml-schedule-model.json"))?.let {
            scheduleModel.set(it)
            logger.info("Loaded schedule model ({} trees)", it.trees.size)
        }
    }

    // ── Feature helpers ─────────────────────────────────────────────────────

    private data class CdnRaw(
        val hour: Int, val dow: Int, val isWeekend: Int,
        val recentAvg: Double, val recentStd: Double,
        val failures: Int, val probeMs: Double
    )

    private fun cdnRawFeatures(host: String, now: Long): CdnRaw? {
        val zdt = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        val snap = CdnSelector.snapshot(now)[host]
        val recent = snap?.let {
            // use lastDuration + global as proxy when ring not exported fully
            val avg = if (!it.globalEwma.isNaN()) it.globalEwma else it.lastDurationMs.toDouble()
            avg
        } ?: 0.0
        val std = if (recent > 0) recent * 0.25 else 0.0
        val probe = snap?.let { CdnSelector.probeSnapshot(host)?.durationMs } ?: Double.NaN
        return CdnRaw(
            hour = zdt.hour,
            dow = zdt.dayOfWeek.value - 1,
            isWeekend = if (zdt.dayOfWeek.value >= 6) 1 else 0,
            recentAvg = recent,
            recentStd = std,
            failures = snap?.failures ?: 0,
            probeMs = if (probe.isNaN()) -1.0 else probe
        )
    }

    private fun cdnFeatureVector(host: String, now: Long): DoubleArray? {
        val raw = cdnRawFeatures(host, now) ?: return null
        val snap = CdnSelector.snapshot(now)[host]
        val global = snap?.globalEwma?.takeIf { !it.isNaN() } ?: raw.recentAvg
        val hourE = snap?.let {
            val h = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).hour
            it.hourEwma.getOrNull(h)?.takeIf { v -> !v.isNaN() }
        } ?: global
        val sr = snap?.let {
            val t = it.totalSuccesses + it.totalErrors
            if (t > 0) it.totalSuccesses.toDouble() / t else 0.5
        } ?: 0.5
        return doubleArrayOf(
            raw.hour.toDouble(),
            raw.dow.toDouble(),
            raw.isWeekend.toDouble(),
            hostHash(host),
            raw.recentAvg,
            raw.recentStd,
            raw.failures.toDouble(),
            raw.probeMs,
            global,
            hourE,
            sr
        )
    }

    private fun scheduleFeatureVector(roomId: Long, hour: Int, now: Long): DoubleArray? {
        val zdt = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        val raw = ModelSchedule.rawStats(roomId)
        val hourHist = ModelSchedule.getDistribution(roomId)?.getOrNull(hour) ?: 0.0
        val weekdayHist = ModelSchedule.getDistribution(roomId, ModelSchedule.DayType.WEEKDAY)?.getOrNull(hour) ?: 0.0
        val weekendHist = ModelSchedule.getDistribution(roomId, ModelSchedule.DayType.WEEKEND)?.getOrNull(hour) ?: 0.0
        val hoursSince = if (raw != null && raw.lastStartTime > 0) {
            ((now - raw.lastStartTime).toDouble() / 3_600_000.0).coerceIn(0.0, 720.0)
        } else 48.0
        val totalCount = raw?.totalCount ?: 0L
        return doubleArrayOf(
            hour.toDouble(),
            (zdt.dayOfWeek.value - 1).toDouble(),
            if (zdt.dayOfWeek.value >= 6) 1.0 else 0.0,
            roomHash(roomId),
            hourHist,
            weekdayHist,
            weekendHist,
            hoursSince,
            kotlin.math.ln((totalCount + 1).toDouble())
        )
    }

    private fun hostHash(host: String): Double =
        (host.hashCode().toLong() and 0x7fffffffL).toDouble() % 10007

    private fun roomHash(roomId: Long): Double =
        (roomId and 0x7fffffffL).toDouble() % 10007
}
