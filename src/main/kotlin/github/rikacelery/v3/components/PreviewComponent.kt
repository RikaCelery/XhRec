package github.rikacelery.v3.components

import github.rikacelery.v3.api.ApiClient
import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.data.Room
import github.rikacelery.v3.data.RoomStatus
import github.rikacelery.v3.data.RuntimeTuning
import github.rikacelery.v3.events.GetArmedRoomIds
import github.rikacelery.v3.events.GetHostsConfig
import github.rikacelery.v3.events.GetRooms
import github.rikacelery.v3.events.GetSessions
import github.rikacelery.v3.events.HostsConfigResponse
import github.rikacelery.v3.utils.DefaultHttpClientProvider
import github.rikacelery.v3.utils.HttpClientProvider
import github.rikacelery.v3.utils.runProcessStreaming
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The composited strip the WebUI scrubs through: [samples] are laid out left-to-right, top-to-bottom
 * in a [columns] x [rows] grid of [cellWidth] x [cellHeight] cells, oldest first.
 */
data class PreviewSprite(
    val file: File,
    val columns: Int,
    val rows: Int,
    val cellWidth: Int,
    val cellHeight: Int,
    val samples: List<File>
)

/**
 * The rooms a sampling pass should cover: every room being recorded, plus every armed room the
 * platform last reported as public. Recording rooms are not filtered by status — a paid show is
 * still recorded, and the response decides what is storable anyway — while an armed room that is
 * `off` is skipped before it costs a request.
 *
 * A room missing from the room list is dropped: without a name there is no broadcast to ask about.
 */
internal fun previewTargets(
    rooms: List<Room>,
    sessions: List<RoomSession>,
    armed: Set<Long>
): List<Pair<Long, String>> {
    val nameById = rooms.associate { it.id to it.name }
    val statusById = rooms.associate { it.id to it.status }
    val recording = sessions.asSequence()
        .filter { it.state == SessionState.Recording }
        .map { it.roomId }
    val armedPublic = armed.asSequence()
        .filter { RoomStatus.isPublic(statusById[it].orEmpty()) }
    return (recording + armedPublic)
        .distinct()
        .mapNotNull { roomId -> nameById[roomId]?.let { roomId to it } }
        .sortedBy { it.first }
        .toList()
}

/** Drives [PreviewComponent]; one tick per [RuntimeTuning.previewSampleInterval]. */
sealed interface PreviewMsg

data object PreviewTick : PreviewMsg

/**
 * Keeps the last two hours of site snapshots for the rooms that are recording or armed, and hands
 * the WebUI one composited image to scrub through.
 *
 * Armed rooms are sampled as well as recording ones so the strip is already warm when a broadcast
 * starts: an armed room is minutes away from being recorded, and the frames from just before the
 * session are exactly the ones worth having at the left edge of the strip. The platform status in
 * the last poll gates it — an armed room that is `off` costs no request.
 *
 * The WebUI used to fetch these thumbnails itself — it called the site's broadcast API and loaded
 * the CDN image URL into an `<img>`, so every open tab talked to the site directly and the preview
 * died whenever the site was unreachable from the browser. Here the sampler runs next to the
 * recorder, stores at most [MAX_SAMPLES] five-minute slots per room under `<tmp>/preview/<roomId>/`,
 * and the browser only ever talks to us.
 *
 * The slot grid is deliberately dense: time a room spends offline is not represented at all, so a
 * strip never shows empty cells, and the next broadcast simply keeps filling it. Only `public`
 * shows are stored — a ticket/private/p2p snapshot is the placeholder the site serves in place of
 * the show, and keeping it would put a grey card in the strip.
 */
class PreviewComponent(
    private val requestBus: RequestBus,
    private val apiClient: ApiClient,
    eventBus: EventBus,
    parentScope: CoroutineScope,
    private val tmpDir: File,
    private val httpClientProvider: HttpClientProvider = DefaultHttpClientProvider,
    private val runtimeTuning: RuntimeTuning = RuntimeTuning(),
) : Actor<PreviewMsg>("PreviewComponent", eventBus, parentScope) {

    private val root = File(tmpDir, ROOT_DIR)

    /** Last snapshot timestamp stored per room, so an unchanged frame is not stored twice. */
    private val lastSnapshotTs = ConcurrentHashMap<Long, Long>()

    /** Signature of the sample set the cached sprite was built from, per room. */
    private val spriteSignature = ConcurrentHashMap<Long, String>()
    private val spriteLock = Mutex()

    override suspend fun onStart(scope: CoroutineScope) {
        root.mkdirs()
        scope.launch {
            while (isActive) {
                tell(PreviewTick)
                delay(runtimeTuning.previewSampleInterval)
            }
        }
    }

    override suspend fun handle(msg: PreviewMsg) {
        if (msg !is PreviewTick) return
        val targets = try {
            previewTargets(
                rooms = requestBus.request(GetRooms),
                sessions = requestBus.request(GetSessions),
                armed = requestBus.request<List<Long>>(GetArmedRoomIds).toSet()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug("preview: room list unavailable: {}", e.message)
            return
        }
        if (targets.isEmpty()) return
        // Read once per tick, not per room: this is the folder the WebUI's host settings write to,
        // and a sample every five minutes does not need to observe a change mid-tick.
        val thumbHost = try {
            requestBus.request<HostsConfigResponse>(GetHostsConfig).hosts.thumbHost
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ""
        }.ifBlank { DEFAULT_THUMB_HOST }
        val semaphore = Semaphore(MAX_PARALLEL)
        withContext(Dispatchers.IO) {
            targets.map { target ->
                async { semaphore.withPermit { sample(target.first, target.second, thumbHost) } }
            }.awaitAll()
        }
    }

    private suspend fun sample(roomId: Long, roomName: String, thumbHost: String) {
        val slot = System.currentTimeMillis() / SLOT_MS * SLOT_MS
        val target = File(File(root, roomId.toString()), "$slot.jpg")
        if (target.exists()) return

        val info: JsonObject = try {
            apiClient.roomFetchBroadcastInfo(roomName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug("preview: roomId={} broadcast info failed: {}", roomId, e.message)
            return
        }
        // `api/front/v1/broadcasts/<name>` answers `{"item": {...}}` — the same shape the room poll
        // reads `item.status` from. (The `user.user.*` shape belongs to the v2 `models/.../cam` API.)
        val item = info["item"]?.jsonObject ?: return
        val status = item["status"]?.jsonPrimitive?.content ?: return
        if (status != PUBLIC_STATUS) return
        val snapshotTs = item["snapshotTimestamp"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
        if (lastSnapshotTs[roomId] == snapshotTs) return
        val modelId = item["modelId"]?.jsonPrimitive?.content?.toLongOrNull() ?: roomId

        val url = "https://$thumbHost/thumbs/$snapshotTs/$modelId"
        val bytes = try {
            httpClientProvider.direct("thumb_${roomId % 8}")
                .get(url) { header(HttpHeaders.UserAgent, BROWSER_UA) }
                .body<ByteArray>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug("preview: roomId={} snapshot download failed: {}", roomId, e.message)
            return
        }
        if (bytes.isEmpty()) return

        target.parentFile?.mkdirs()
        val raw = File(target.parentFile, "$slot.raw")
        try {
            raw.writeBytes(bytes)
            // Normalise on the way in: every stored sample is the same size, which is what lets the
            // sprite be a single ffmpeg pass instead of a per-image decode we would have to write.
            runProcessStreaming(
                { line -> logger.debug("[ffmpeg] {}", line) },
                "ffmpeg", "-hide_banner", "-v", "error", "-y", "-i", raw.absolutePath,
                "-frames:v", "1", "-vf", NORMALISE,
                "-q:v", "4", target.absolutePath
            )
            lastSnapshotTs[roomId] = snapshotTs
            prune(target.parentFile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug("preview: roomId={} snapshot transcode failed: {}", roomId, e.message)
        } finally {
            raw.delete()
        }
    }

    /** Keeps the newest [MAX_SAMPLES] samples and drops anything older than [MAX_AGE_MS]. */
    internal fun prune(dir: File?) {
        val files = sampleFiles(dir) ?: return
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        files.forEachIndexed { index, file ->
            val slot = file.nameWithoutExtension.toLongOrNull() ?: return@forEachIndexed
            val beyondCap = files.size - index > MAX_SAMPLES
            if (beyondCap || slot < cutoff) file.delete()
        }
    }

    private fun sampleFiles(dir: File?): List<File>? =
        // Sprite artifacts start with an underscore, so this is every stored sample and nothing else.
        dir?.listFiles { file -> file.isFile && file.name.endsWith(".jpg") && !file.name.startsWith("_") }
            ?.sortedBy { it.name }

    fun samples(roomId: Long): List<File> = sampleFiles(File(root, roomId.toString())) ?: emptyList()

    fun latest(roomId: Long): File? = samples(roomId).lastOrNull()

    /**
     * The scrubbing strip: every sample scaled to one grid cell, oldest first, padding the grid with
     * the newest sample (the browser never shows a padding cell, it only needs a full tile).
     */
    suspend fun spriteInfo(roomId: Long): PreviewSprite? {
        val file = sprite(roomId) ?: return null
        return PreviewSprite(
            file = file,
            columns = GRID_COLS,
            rows = GRID_ROWS,
            cellWidth = CELL_WIDTH,
            cellHeight = CELL_HEIGHT,
            samples = samples(roomId)
        )
    }

    private suspend fun sprite(roomId: Long): File? {
        val samples = samples(roomId)
        if (samples.isEmpty()) return null
        val signature = samples.joinToString(",") { it.name }
        val target = File(File(root, roomId.toString()), SPRITE_NAME)
        if (spriteSignature[roomId] == signature && target.exists()) return target
        return spriteLock.withLock {
            if (spriteSignature[roomId] == signature && target.exists()) return@withLock target
            // The scratch name keeps a .jpg extension on purpose: ffmpeg infers the muxer from the
            // extension, and a ".tmp" output fails with "Unable to find a suitable output format".
            val raw = File(target.parentFile, SPRITE_TMP_NAME)
            try {
                val inputs = samples.map { it.absolutePath }.toMutableList()
                while (inputs.size < GRID_COLS * GRID_ROWS) inputs.add(samples.last().absolutePath)
                val command = mutableListOf("ffmpeg", "-hide_banner", "-v", "error", "-y")
                inputs.forEach { path -> command += listOf("-i", path) }
                command += listOf(
                    "-filter_complex",
                    buildString {
                        inputs.indices.forEach { append("[$it:v]") }
                        append("concat=n=${inputs.size}:v=1:a=0,scale=$CELL_WIDTH:$CELL_HEIGHT,")
                        append("tile=${GRID_COLS}x${GRID_ROWS}[out]")
                    },
                    "-map", "[out]", "-frames:v", "1", "-q:v", "5", raw.absolutePath
                )
                runProcessStreaming({ line -> logger.debug("[ffmpeg] {}", line) }, *command.toTypedArray())
                withContext(Dispatchers.IO) {
                    target.delete()
                    if (!raw.renameTo(target)) return@withContext
                    spriteSignature[roomId] = signature
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("preview: sprite for roomId={} failed: {}", roomId, e.message)
            } finally {
                raw.delete()
            }
            target.takeIf { it.exists() }
        }
    }

    override suspend fun diagnose(section: String, args: Map<String, String>): JsonObject {
        val rooms = root.listFiles()?.filter { it.isDirectory } ?: emptyList()
        return baseDiagnose(buildJsonObject {
            put("cacheDir", root.absolutePath)
            put("slotSeconds", SLOT_MS / 1000)
            put("maxSamples", MAX_SAMPLES)
            put("rooms", rooms.size)
            put("entries", buildJsonObject {
                rooms.sortedBy { it.name }.forEach { dir ->
                    val samples = sampleFiles(dir) ?: return@forEach
                    if (samples.isEmpty()) return@forEach
                    put(dir.name, buildJsonObject {
                        put("samples", samples.size)
                        put("oldest", samples.first().nameWithoutExtension)
                        put("newest", samples.last().nameWithoutExtension)
                        put("lastSnapshotTs", lastSnapshotTs[dir.name.toLongOrNull()] ?: 0L)
                    })
                }
            })
        })
    }

    private companion object {
        const val ROOT_DIR = "preview"
        const val SPRITE_NAME = "_sprite.jpg"
        const val SPRITE_TMP_NAME = "_sprite.tmp.jpg"
        const val PUBLIC_STATUS = "public"
        const val DEFAULT_THUMB_HOST = "img.doppiocdn.org"
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"
        /** Stored sample size: 16:9, the shape the platform serves. */
        const val SAMPLE_WIDTH = 480
        const val SAMPLE_HEIGHT = 270

        /**
         * Sprite geometry. Cells are smaller than the stored samples because the strip is a hover
         * preview: 6x4 of these is one ~120 KB JPEG that carries all 24 frames in a single request.
         */
        const val GRID_COLS = 6
        const val GRID_ROWS = 4
        const val CELL_WIDTH = 320
        const val CELL_HEIGHT = 180

        const val NORMALISE =
            "scale=$SAMPLE_WIDTH:$SAMPLE_HEIGHT:force_original_aspect_ratio=decrease," +
                "pad=$SAMPLE_WIDTH:$SAMPLE_HEIGHT:(ow-iw)/2:(oh-ih)/2:color=black"

        const val SLOT_MS = 5 * 60 * 1000L
        const val MAX_SAMPLES = 24
        const val MAX_AGE_MS = 2 * 60 * 60 * 1000L
        const val MAX_PARALLEL = 4
    }
}
