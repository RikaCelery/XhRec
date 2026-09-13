package github.rikacelery.cutter.ffmpeg

import github.rikacelery.cutter.config.CutConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class ProbeStream(
    val index: Int,
    val type: String,
    val codec: String,
    val width: Int? = null,
    val height: Int? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    /** Frames per second, needed for frame-accurate stepping in the editor. */
    val fps: Double? = null,
    /** Cover art / embedded thumbnails are video streams that behave differently. */
    val attachedPic: Boolean = false
)

@Serializable
data class MediaProbe(
    val durationSeconds: Double?,
    val streams: List<ProbeStream>
) {
    val hasVideo: Boolean get() = streams.any { it.type == "video" && !it.attachedPic }
    val hasAudio: Boolean get() = streams.any { it.type == "audio" }
    val hasAttachedPic: Boolean get() = streams.any { it.attachedPic }
}

/**
 * ffprobe wrapper plus a process-wide cache.
 *
 * Probing a 1.2 GB file over CIFS costs a few hundred ms, so results are memoised
 * in memory and on disk. Concurrent probes of the same id are coalesced.
 */
object ProbeCache {

    private val log = LoggerFactory.getLogger(ProbeCache::class.java)
    private val memory = ConcurrentHashMap<String, MediaProbe>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<MediaProbe>>()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    suspend fun probe(config: CutConfig, id: String, file: File): MediaProbe {
        memory[id]?.let { return it }
        readDisk(config, id)?.let {
            memory[id] = it
            return it
        }
        while (true) {
            inFlight[id]?.let { return it.await() }
            val fresh = CompletableDeferred<MediaProbe>()
            if (inFlight.putIfAbsent(id, fresh) == null) {
                try {
                    val probe = runProbe(config, file)
                    memory[id] = probe
                    writeDisk(config, id, probe)
                    fresh.complete(probe)
                    return probe
                } catch (e: Throwable) {
                    fresh.completeExceptionally(e)
                    throw e
                } finally {
                    inFlight.remove(id)
                }
            }
        }
    }

    fun cached(id: String): MediaProbe? = memory[id]

    fun invalidate(id: String) {
        memory.remove(id)
    }

    private suspend fun runProbe(config: CutConfig, file: File): MediaProbe =
        withContext(Dispatchers.IO) {
            val raw = Proc.capture(
                config.ffprobe, "-v", "error",
                "-print_format", "json",
                "-show_format", "-show_streams",
                file.absolutePath
            )
            parse(raw)
        }

    fun parse(raw: String): MediaProbe {
        val root = json.parseToJsonElement(raw).jsonObject
        val duration = root["format"]?.jsonObject?.get("duration")?.jsonPrimitive?.doubleOrNull
        val streams = root["streams"]?.jsonArray?.map { element ->
            val s = element.jsonObject
            val disposition = s["disposition"]?.jsonObject
            ProbeStream(
                index = s.int("index") ?: 0,
                type = s.str("codec_type") ?: "unknown",
                codec = s.str("codec_name") ?: "unknown",
                width = s.int("width"),
                height = s.int("height"),
                sampleRate = s.str("sample_rate")?.toIntOrNull(),
                channels = s.int("channels"),
                fps = parseRational(s.str("r_frame_rate")) ?: parseRational(s.str("avg_frame_rate")),
                attachedPic = disposition?.get("attached_pic")?.jsonPrimitive?.intOrNull == 1
            )
        } ?: emptyList()
        return MediaProbe(duration, streams)
    }

    /** ffprobe reports frame rates as `30000/1001` style rationals. */
    fun parseRational(text: String?): Double? {
        if (text.isNullOrBlank()) return null
        val parts = text.split('/')
        val value = when (parts.size) {
            1 -> parts[0].toDoubleOrNull()
            2 -> {
                val numerator = parts[0].toDoubleOrNull()
                val denominator = parts[1].toDoubleOrNull()
                if (numerator == null || denominator == null || denominator == 0.0) null
                else numerator / denominator
            }
            else -> null
        }
        return value?.takeIf { it > 0.0 && it < 1000.0 }
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun cacheFile(config: CutConfig, id: String): File =
        File(config.cacheDir, "probe/$id.json")

    private suspend fun readDisk(config: CutConfig, id: String): MediaProbe? =
        withContext(Dispatchers.IO) {
            val file = cacheFile(config, id)
            if (!file.isFile) return@withContext null
            runCatching { json.decodeFromString(MediaProbe.serializer(), file.readText()) }
                .onFailure { log.debug("ignoring bad probe cache {}: {}", file.name, it.message) }
                .getOrNull()
        }

    private suspend fun writeDisk(config: CutConfig, id: String, probe: MediaProbe) =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = Proc.ensureDir(File(config.cacheDir, "probe"))
                File(dir, "$id.json").writeText(json.encodeToString(MediaProbe.serializer(), probe))
            }.onFailure { log.debug("could not cache probe for {}: {}", id, it.message) }
        }
}
