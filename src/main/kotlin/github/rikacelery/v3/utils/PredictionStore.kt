package github.rikacelery.v3.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Persists prediction data (CDN selector stats + model schedule stats) to disk
 * so learning survives restarts.
 *
 * Data is saved atomically to [filePath] on a debounced interval and on stop.
 */
class PredictionStore(
    private val filePath: String = "xhrec-predictions.json",
    private val appScope: CoroutineScope,
    private val saveIntervalMs: Long = 60_000
) {
    private val logger = LoggerFactory.getLogger("PredictionStore")
    private var saveJob: Job? = null

    init {
        load()
    }

    /**
     * Load persisted data from disk into CdnSelector and ModelSchedule.
     */
    fun load() {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                logger.info("No prediction data file at {}; starting fresh", filePath)
                return
            }
            val text = file.readText()
            if (text.isBlank()) return

            val root = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
            root["cdn"]?.jsonObject?.let { cdnObj ->
                CdnSelector.importState(cdnObj.toString())
                logger.info("Loaded CDN selector data for {} hosts", CdnSelector.hosts.size)
            }
            root["modelSchedule"]?.jsonObject?.let { modelObj ->
                ModelSchedule.importState(modelObj.toString())
                logger.info("Loaded model schedule data for {} rooms", ModelSchedule.getAllRoomIds().size)
            }
        } catch (e: Exception) {
            logger.warn("Failed to load prediction data: ${e.message}")
        }
    }

    /**
     * Persist current in-memory state to disk atomically.
     */
    suspend fun save() {
        try {
            val payload = buildString {
                append("{\"cdn\":")
                append(CdnSelector.exportState())
                append(",\"modelSchedule\":")
                append(ModelSchedule.exportState())
                append("}")
            }
            val file = File(filePath)
            withContext(Dispatchers.IO) {
                val tmp = File(file.path + ".tmp")
                tmp.writeText(payload)
                if (file.exists()) file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            logger.warn("Failed to save prediction data: ${e.message}")
        }
    }

    /**
     * Start periodic auto-save.
     */
    fun start() {
        if (saveJob != null) return
        saveJob = appScope.launch {
            while (true) {
                delay(saveIntervalMs)
                save()
            }
        }
        logger.info("Prediction auto-save started (every ${saveIntervalMs}ms)")
    }

    /**
     * Stop auto-save and persist final state.
     */
    suspend fun stop() {
        saveJob?.cancel()
        saveJob = null
        save()
        logger.info("Prediction store stopped and saved")
    }
}
