package github.rikacelery.v3.ml

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ring-buffer sample store for ML training.
 * Persisted under the process working directory (Docker WORKDIR=/config).
 *
 * Files:
 *   - ml-cdn-samples.jsonl
 *   - ml-schedule-samples.jsonl
 */
object PredictionSampleStore {
    private val logger = LoggerFactory.getLogger(PredictionSampleStore::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    var dataDir: File = File(".")
        private set

    private const val MAX_CDN = 8000
    private const val MAX_SCHEDULE = 8000

    private val cdnSamples = CopyOnWriteArrayList<CdnSample>()
    private val scheduleSamples = CopyOnWriteArrayList<ScheduleSample>()
    private val cdnDirty = AtomicInteger(0)
    private val scheduleDirty = AtomicInteger(0)

    @Serializable
    data class CdnSample(
        val ts: Long,
        val host: String,
        val durationMs: Double,
        val hour: Int,
        val dow: Int,
        val isWeekend: Int,
        val recentAvg: Double,
        val recentStd: Double,
        val failures: Int,
        val probeMs: Double,
        val success: Int
    )

    @Serializable
    data class ScheduleSample(
        val ts: Long,
        val roomId: Long,
        val hour: Int,
        val dow: Int,
        val isWeekend: Int,
        val hourHist: Double,
        val weekdayHist: Double,
        val weekendHist: Double,
        val hoursSinceLast: Double,
        val totalCount: Int,
        /** 1 if room went live in this hour slot (positive example). */
        val wentLive: Int
    )

    fun configure(dir: File) {
        dataDir = dir
        dir.mkdirs()
        loadAll()
    }

    /** Clear in-memory samples (tests). Does not delete files unless [deleteFiles]. */
    fun clear(deleteFiles: Boolean = false) {
        cdnSamples.clear()
        scheduleSamples.clear()
        cdnDirty.set(0)
        scheduleDirty.set(0)
        if (deleteFiles) {
            cdnFile().delete()
            scheduleFile().delete()
        }
    }

    fun loadAll() {
        loadCdn()
        loadSchedule()
    }

    fun recordCdn(sample: CdnSample) {
        cdnSamples.add(sample)
        while (cdnSamples.size > MAX_CDN) cdnSamples.removeAt(0)
        if (cdnDirty.incrementAndGet() >= 50) {
            cdnDirty.set(0)
            persistCdn()
        }
    }

    fun recordSchedule(sample: ScheduleSample) {
        scheduleSamples.add(sample)
        while (scheduleSamples.size > MAX_SCHEDULE) scheduleSamples.removeAt(0)
        if (scheduleDirty.incrementAndGet() >= 20) {
            scheduleDirty.set(0)
            persistSchedule()
        }
    }

    fun cdnSnapshot(): List<CdnSample> = cdnSamples.toList()
    fun scheduleSnapshot(): List<ScheduleSample> = scheduleSamples.toList()

    fun cdnCount(): Int = cdnSamples.size
    fun scheduleCount(): Int = scheduleSamples.size

    fun flush() {
        persistCdn()
        persistSchedule()
    }

    private fun cdnFile() = File(dataDir, "ml-cdn-samples.jsonl")
    private fun scheduleFile() = File(dataDir, "ml-schedule-samples.jsonl")

    private fun loadCdn() {
        val f = cdnFile()
        if (!f.exists()) return
        try {
            val lines = f.readLines().filter { it.isNotBlank() }
            val take = lines.takeLast(MAX_CDN)
            cdnSamples.clear()
            take.forEach { line ->
                try {
                    cdnSamples.add(json.decodeFromString(CdnSample.serializer(), line))
                } catch (_: Exception) { }
            }
            logger.info("Loaded {} CDN ML samples from {}", cdnSamples.size, f.absolutePath)
        } catch (e: Exception) {
            logger.warn("Failed to load CDN samples: {}", e.message)
        }
    }

    private fun loadSchedule() {
        val f = scheduleFile()
        if (!f.exists()) return
        try {
            val lines = f.readLines().filter { it.isNotBlank() }
            val take = lines.takeLast(MAX_SCHEDULE)
            scheduleSamples.clear()
            take.forEach { line ->
                try {
                    scheduleSamples.add(json.decodeFromString(ScheduleSample.serializer(), line))
                } catch (_: Exception) { }
            }
            logger.info("Loaded {} schedule ML samples from {}", scheduleSamples.size, f.absolutePath)
        } catch (e: Exception) {
            logger.warn("Failed to load schedule samples: {}", e.message)
        }
    }

    @Synchronized
    private fun persistCdn() {
        try {
            dataDir.mkdirs()
            val f = cdnFile()
            val tmp = File(dataDir, "ml-cdn-samples.jsonl.tmp")
            tmp.writeText(cdnSamples.joinToString("\n") { json.encodeToString(CdnSample.serializer(), it) } +
                if (cdnSamples.isNotEmpty()) "\n" else "")
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (e: Exception) {
            logger.warn("Failed to persist CDN samples: {}", e.message)
        }
    }

    @Synchronized
    private fun persistSchedule() {
        try {
            dataDir.mkdirs()
            val f = scheduleFile()
            val tmp = File(dataDir, "ml-schedule-samples.jsonl.tmp")
            tmp.writeText(scheduleSamples.joinToString("\n") { json.encodeToString(ScheduleSample.serializer(), it) } +
                if (scheduleSamples.isNotEmpty()) "\n" else "")
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (e: Exception) {
            logger.warn("Failed to persist schedule samples: {}", e.message)
        }
    }
}
