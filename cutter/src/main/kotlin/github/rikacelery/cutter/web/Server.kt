package github.rikacelery.cutter.web

import github.rikacelery.cutter.config.CutConfig
import github.rikacelery.cutter.config.SettingsStore
import github.rikacelery.cutter.events.DEFAULT_EVENT_NUDGE_SECONDS
import github.rikacelery.cutter.events.ParsedEvent
import github.rikacelery.cutter.events.Timeline
import github.rikacelery.cutter.events.TimelineService
import github.rikacelery.cutter.ffmpeg.AudioLanes
import github.rikacelery.cutter.ffmpeg.CutEngine
import github.rikacelery.cutter.ffmpeg.ExportJob
import github.rikacelery.cutter.ffmpeg.ExportQueue
import github.rikacelery.cutter.ffmpeg.ExportRequest
import github.rikacelery.cutter.ffmpeg.SnapMode
import github.rikacelery.cutter.ffmpeg.PreviewQuality
import github.rikacelery.cutter.ffmpeg.ProxyService
import github.rikacelery.cutter.ffmpeg.PreviewServer
import github.rikacelery.cutter.ffmpeg.Proc
import github.rikacelery.cutter.ffmpeg.ProbeCache
import github.rikacelery.cutter.media.MediaDeleter
import github.rikacelery.cutter.media.MediaEntry
import github.rikacelery.cutter.media.MediaIndex
import github.rikacelery.cutter.project.CutProject
import github.rikacelery.cutter.project.Llc
import github.rikacelery.cutter.suggest.CutSuggester
import github.rikacelery.cutter.suggest.SuggestPrefs
import github.rikacelery.cutter.suggest.SuggestedBlock
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.request.receive
import io.ktor.server.routing.delete
import io.ktor.server.routing.put
import io.ktor.server.response.respond
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

class CutServer(
    private val config: CutConfig,
    private val index: MediaIndex,
    private val timelines: TimelineService,
    private val previews: PreviewServer,
    private val proxies: ProxyService,
    private val audio: AudioLanes,
    private val cuts: CutEngine,
    private val exports: ExportQueue,
    private val deleter: MediaDeleter,
    private val settings: SettingsStore,
    private val scope: CoroutineScope
) {
    private val log = LoggerFactory.getLogger(CutServer::class.java)

    private val uiHtml: String by lazy {
        javaClass.getResource("/cutter.html")?.readText()
            ?: "<h1>cutter.html missing from resources</h1>"
    }

    private val ffmpegVersion: String? by lazy { Proc.probeTool(config.ffmpeg) }
    private val ffprobeVersion: String? by lazy { Proc.probeTool(config.ffprobe) }

    fun start(wait: Boolean = true) {
        embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
            module()
        }.start(wait = wait)
    }

    private fun Application.module() {
        // Required by raw (lossless) mode: the browser plays the source file itself
        // and seeks by issuing Range requests.
        install(PartialContent)

        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            })
        }

        routing {
            get("/") { call.respondText(uiHtml, ContentType.Text.Html) }

            get("/api/health") {
                call.respond(
                    HealthDto(
                        status = "ok",
                        version = VERSION,
                        ffmpeg = ffmpegVersion,
                        ffprobe = ffprobeVersion,
                        mediaRootCount = config.mediaRoots.size,
                        zone = config.zone.id,
                        scanning = index.scanning,
                        generation = index.generation,
                        lastScanAt = index.lastScanAt,
                        lastScanMillis = index.lastScanMillis,
                        indexed = index.entries.size
                    )
                )
            }

            get("/api/config") {
                call.respond(
                    ConfigDto(
                        zone = config.zone.id,
                        previewSegmentSeconds = config.previewSegmentSeconds,
                        deleteEnabled = deleteProbe() == null,
                        deleteBlockedReason = deleteProbe()?.reason
                    )
                )
            }

            /**
             * Instance-wide settings. Server-side rather than `localStorage` because they
             * change what happens to files on the NAS, so they must mean the same thing
             * for every browser.
             */
            get("/api/settings") {
                val v = settings.value
                call.respond(
                    SettingsDto(
                        deleteSourceAfterExport = v.deleteSourceAfterExport,
                        deleteEnabled = deleteProbe() == null,
                        error = settings.lastError
                    )
                )
            }

            put("/api/settings") {
                val body = call.receive<SettingsUpdateDto>()
                val updated = settings.update { it.copy(deleteSourceAfterExport = body.deleteSourceAfterExport) }
                call.respond(
                    SettingsDto(
                        deleteSourceAfterExport = updated.deleteSourceAfterExport,
                        deleteEnabled = deleteProbe() == null,
                        error = settings.lastError
                    )
                )
            }

            post("/api/library/scan") {
                if (index.scanning) {
                    call.respond(HttpStatusCode.Accepted, ScanResultDto(index.entries.size, index.generation, 0))
                    return@post
                }
                val started = System.currentTimeMillis()
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        index.scan()
                        // Heat is derived from the event timeline, so a rescan has to
                        // refresh it too — otherwise the "sort by toy heat" view keeps
                        // showing values computed before the last semantics change.
                        timelines.enrichAll(index.entries)
                    }.onFailure { log.error("library scan failed", it) }
                }
                call.respond(
                    HttpStatusCode.Accepted,
                    ScanResultDto(index.entries.size, index.generation, System.currentTimeMillis() - started)
                )
            }

            get("/api/library") {
                val q = call.request.queryParameters["q"]?.trim()?.lowercase()
                val room = call.request.queryParameters["room"]?.takeIf { it.isNotBlank() }
                val hasEvents = call.request.queryParameters["hasEvents"]?.toBooleanStrictOrNull()
                val sort = call.request.queryParameters["sort"] ?: "date"
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 1000)
                val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)

                val all = index.entries
                val rooms = all.groupingBy { it.room }.eachCount()
                    .map { RoomFacetDto(it.key, it.value) }
                    .sortedByDescending { it.count }

                var filtered = all.asSequence()
                if (!q.isNullOrEmpty()) {
                    filtered = filtered.filter {
                        it.fileName.lowercase().contains(q) || it.room.lowercase().contains(q)
                    }
                }
                if (room != null) filtered = filtered.filter { it.room == room }
                if (hasEvents != null) filtered = filtered.filter { it.hasEvents == hasEvents }

                val sorted = when (sort) {
                    "size" -> filtered.sortedByDescending { it.sizeBytes }
                    "name" -> filtered.sortedBy { it.fileName }
                    "room" -> filtered.sortedBy { it.room }
                    "heat" -> filtered.sortedByDescending { it.heat?.score ?: -1.0 }
                    "duration" -> filtered.sortedByDescending { it.nameDurationSeconds ?: 0L }
                    // The recording start parsed from the file name is a truer date
                    // than mtime, which only reflects when post-processing moved it.
                    else -> filtered.sortedByDescending { it.startEpochSeconds ?: (it.modifiedAt / 1000) }
                }.toList()

                val page = sorted.drop(offset).take(limit).map { it.toDto() }
                call.respond(
                    LibraryPageDto(
                        generation = index.generation,
                        total = sorted.size,
                        offset = offset,
                        items = page,
                        rooms = rooms,
                        enriching = false
                    )
                )
            }

            get("/api/media/{id}") {
                val id = call.parameters["id"].orEmpty()
                val entry = index.find(id)
                if (entry == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorDto("unknown media id"))
                    return@get
                }
                val file = index.resolve(entry)
                if (file == null) {
                    call.respond(HttpStatusCode.Gone, ErrorDto("file no longer present", entry.relPath))
                    return@get
                }
                call.respond(entry.toDetail(file))
            }

            /**
             * Permanently removes a recording: the video, its `.event` sidecar, and the
             * cache artefacts derived from it.
             *
             * `DELETE` on the media resource rather than a `POST /delete` verb route, so
             * the operation reads as what it is. It is not idempotent-friendly on purpose:
             * a second delete answers 404, because by then the id genuinely is unknown.
             */
            delete("/api/media/{id}") {
                val id = call.parameters["id"].orEmpty()
                val entry = index.find(id)
                if (entry == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorDto("unknown media id"))
                    return@delete
                }
                // No pre-check against the media root here: the delete itself is the only
                // authority on whether *this* recording can be unlinked, and a probe of a
                // different directory would block deletes that would have succeeded.
                val result = deleter.delete(entry)
                if (!result.ok) {
                    // A read-only mount is a configuration state the operator can fix
                    // (the shipped deployment mounts the media root `ro`), so it is a 409
                    // with the OS reason rather than a bare 500.
                    val status = if (result.permissionDenied) HttpStatusCode.Conflict
                    else HttpStatusCode.InternalServerError
                    call.respond(
                        status,
                        ErrorDto(
                            if (result.permissionDenied) "media root is not writable" else "delete failed",
                            result.failed.joinToString("; ") { "${it.path}: ${it.reason}" }
                        )
                    )
                    return@delete
                }
                call.respond(DeleteResultDto(deleted = result.deleted, fileName = result.fileName))
            }

            get("/api/media/{id}/events") {
                val entry = index.find(call.parameters["id"].orEmpty())
                if (entry == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorDto("unknown media id"))
                    return@get
                }
                val from = call.request.queryParameters["from"]?.toDoubleOrNull()
                val to = call.request.queryParameters["to"]?.toDoubleOrNull()
                val kinds = call.request.queryParameters["kinds"]
                    ?.split(',')?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() }?.toSet()
                val nudge = eventNudge(call)
                val timeline = timelines.timeline(entry, nudge)
                    ?: return@get call.respond(emptyList<EventDto>())
                val filtered = timeline.events.asSequence()
                    .filter { kinds == null || it.kind.name in kinds }
                    .filter { from == null || (it.mediaSeconds ?: Double.NEGATIVE_INFINITY) >= from }
                    .filter { to == null || (it.mediaSeconds ?: Double.POSITIVE_INFINITY) <= to }
                    .map { it.toDto() }
                    .toList()
                call.respond(filtered)
            }

            get("/api/media/{id}/lanes") {
                val entry = index.find(call.parameters["id"].orEmpty())
                if (entry == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorDto("unknown media id"))
                    return@get
                }
                val nudge = eventNudge(call)
                val timeline = timelines.timeline(entry, nudge)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("no event file for this recording"))
                call.respond(entry.toLanes(timeline, nudge))
            }

            // ---- preview: on-demand low-bitrate HLS for slow links ----

            get("/api/media/{id}/preview.m3u8") {
                val entry = resolved(call.parameters["id"].orEmpty()) ?: return@get call.respondNotFound()
                val probe = runCatching { ProbeCache.probe(config, entry.first.id, entry.second) }.getOrNull()
                val duration = probe?.durationSeconds ?: entry.first.nameDurationSeconds?.toDouble()
                if (duration == null || duration <= 0) {
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorDto("unknown duration"))
                    return@get
                }
                val quality = PreviewQuality.from(call.request.queryParameters["q"])
                val body = previews.playlist(entry.first, duration, quality)
                call.respondText(body, ContentType.parse("application/vnd.apple.mpegurl"))
            }

            get("/api/media/{id}/preview/{index}.ts") {
                val entry = resolved(call.parameters["id"].orEmpty()) ?: return@get call.respondNotFound()
                val segIndex = call.parameters["index"]?.removeSuffix(".ts")?.toIntOrNull()
                if (segIndex == null || segIndex < 0) {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad segment index"))
                    return@get
                }
                val quality = PreviewQuality.from(call.request.queryParameters["q"])
                val file = previews.segment(entry.first, entry.second, segIndex, quality)
                if (file == null) {
                    call.respond(HttpStatusCode.InternalServerError, ErrorDto("segment unavailable"))
                    return@get
                }
                call.respondFile(file)
            }

            /**
             * The untouched source, served with byte-range support.
             *
             * This is the lossless preview: no transcode, no quality loss, and the
             * browser seeks by asking for byte ranges. It costs full source bitrate
             * on the wire, so it is an explicit choice rather than the default.
             */
            get("/api/media/{id}/raw") {
                val entry = resolved(call.parameters["id"].orEmpty()) ?: return@get call.respondNotFound()
                call.respondFile(entry.second)
            }

            /** Single frame, for precise trimming when playback is too coarse. */
            get("/api/media/{id}/frame") {
                val entry = resolved(call.parameters["id"].orEmpty()) ?: return@get call.respondNotFound()
                val at = call.request.queryParameters["t"]?.toDoubleOrNull()
                if (at == null || at < 0) {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("missing t"))
                    return@get
                }
                val width = (call.request.queryParameters["w"]?.toIntOrNull() ?: 320).coerceIn(64, 1920)
                val bytes = previews.frame(entry.first, entry.second, at, width)
                if (bytes == null) {
                    call.respond(HttpStatusCode.InternalServerError, ErrorDto("frame unavailable"))
                    return@get
                }
                // Frames are immutable for a given (media, time, width), so let the
                // browser keep them: the client's own LRU covers the current session,
                // this covers reloads and flips between recordings.
                call.response.header(HttpHeaders.CacheControl, "public, max-age=86400, immutable")
                call.respondBytes(bytes, ContentType.Image.JPEG)
            }

            /**
             * Timestamps with a locally cached frame, so the timeline can mark them
             * (After-Effects style). Cheap: one directory listing.
             */
            get("/api/media/{id}/frames/cached") {
                val entry = index.find(call.parameters["id"].orEmpty())
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("unknown media id"))
                val width = (call.request.queryParameters["w"]?.toIntOrNull() ?: 720).coerceIn(64, 1920)
                call.respond(CachedFramesDto(width, previews.cachedFrameTimes(entry.id, width)))
            }

            // ---- local proxy: browser decodes and caches it itself ----

            get("/api/media/{id}/proxy") {
                val entry = index.find(call.parameters["id"].orEmpty())
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("unknown media id"))
                call.respond(proxies.status(entry, proxyHeight(call), proxyFps(call)).toDto())
            }

            post("/api/media/{id}/proxy") {
                val entry = resolved(call.parameters["id"].orEmpty()) ?: return@post call.respondNotFound()
                val probe = runCatching { ProbeCache.probe(config, entry.first.id, entry.second) }.getOrNull()
                val duration = probe?.durationSeconds ?: entry.first.nameDurationSeconds?.toDouble()
                call.respond(proxies.start(entry.first, entry.second, duration, proxyHeight(call), proxyFps(call)).toDto())
            }

            delete("/api/media/{id}/proxy") {
                val entry = index.find(call.parameters["id"].orEmpty())
                    ?: return@delete call.respond(HttpStatusCode.NotFound, ErrorDto("unknown media id"))
                // `h` deletes one resolution; without it every resolution goes.
                val height = call.request.queryParameters["h"]?.toIntOrNull()
                if (height != null) proxies.cancel(entry, height.coerceIn(240, 720), proxyFps(call))
                else proxies.evictAll(entry)
                call.respond(CancelDto(true))
            }

            /** Served with byte ranges, exactly like `/raw` — the browser seeks in it. */
            get("/api/media/{id}/proxy.mp4") {
                val entry = index.find(call.parameters["id"].orEmpty())
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("unknown media id"))
                val file = proxies.file(entry, proxyHeight(call), proxyFps(call))
                if (!file.isFile || file.length() == 0L) {
                    return@get call.respond(HttpStatusCode.NotFound, ErrorDto("proxy not built yet"))
                }
                call.respondFile(file)
            }

            // ---- audio lanes ----
            //
            // Omit from/to for the whole-file overview, which is cached and reused by
            // the client for every coarse zoom level. `-ss`/`-t` are input options
            // inside AudioLanes; putting them after `-i` makes these filters process
            // the entire file regardless.

            get("/api/media/{id}/waveform") {
                val entry = resolved(call.parameters["id"].orEmpty()) ?: return@get call.respondNotFound()
                val file = call.audioLaneImage(entry, spectrogram = false) ?: return@get
                call.respondFile(file)
            }

            get("/api/media/{id}/spectrogram") {
                val entry = resolved(call.parameters["id"].orEmpty()) ?: return@get call.respondNotFound()
                val file = call.audioLaneImage(entry, spectrogram = true) ?: return@get
                call.respondFile(file)
            }

            get("/api/media/{id}/suggestions") {
                val entry = index.find(call.parameters["id"].orEmpty())
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("unknown media id"))
                val timeline = timelines.timeline(entry, eventNudge(call))
                    ?: return@get call.respond(emptyList<SuggestedBlock>())
                val probe = index.resolve(entry)?.let { runCatching { ProbeCache.probe(config, entry.id, it) }.getOrNull() }
                val duration = probe?.durationSeconds ?: entry.nameDurationSeconds?.toDouble()
                    ?: return@get call.respond(HttpStatusCode.UnprocessableEntity, ErrorDto("unknown duration"))
                call.respond(CutSuggester.suggest(timeline, duration, prefsFrom(call.request.queryParameters)))
            }

            get("/api/suggest-presets") {
                call.respond(SuggestPrefs.PRESETS.mapValues { it.value })
            }

            // ---- keyframes ----

            get("/api/media/{id}/keyframes") {
                val entry = resolved(call.parameters["id"].orEmpty()) ?: return@get call.respondNotFound()
                val from = call.request.queryParameters["from"]?.toDoubleOrNull() ?: 0.0
                val to = call.request.queryParameters["to"]?.toDoubleOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("missing to"))
                if (to <= from) return@get call.respond(KeyframeDto(from, to, emptyList()))
                // Defensive bound: listing keyframes means walking the file, so a
                // wide request against a multi-gigabyte recording would be a full
                // scan. The editor only asks for windows it can actually draw.
                if (to - from > MAX_KEYFRAME_WINDOW) {
                    return@get call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorDto("keyframe window too wide", "max ${MAX_KEYFRAME_WINDOW.toInt()}s, asked ${(to - from).toInt()}s")
                    )
                }
                call.respond(KeyframeDto(from, to, cuts.keyframesIn(entry.second, from, to)))
            }

            /** Dry-run: what a cut at `t` would actually produce. */
            get("/api/media/{id}/plan") {
                val entry = resolved(call.parameters["id"].orEmpty()) ?: return@get call.respondNotFound()
                val start = call.request.queryParameters["start"]?.toDoubleOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("missing start"))
                val mode = snapMode(call.request.queryParameters["snap"])
                val frames = cuts.keyframesAround(entry.second, start)
                val snapped = cuts.snap(frames, start, mode)
                call.respond(
                    PlannedCutDto(
                        requestedStart = start,
                        actualStart = snapped,
                        snapDelta = snapped - start,
                        outputName = cuts.outputName(entry.first, snapped, start + 30.0, 1),
                        keyframes = frames
                    )
                )
            }

            // ---- project (.llc) ----

            get("/api/media/{id}/project") {
                val entry = index.find(call.parameters["id"].orEmpty())
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("unknown media id"))
                val file = projectFile(entry.fileName)
                if (!file.isFile) {
                    return@get call.respond(ProjectDto(entry.id, entry.fileName, emptyList()))
                }
                runCatching { Llc.parse(file.readText()) }
                    .onSuccess { project ->
                        call.respond(
                            ProjectDto(
                                mediaId = entry.id,
                                mediaFileName = entry.fileName,
                                segments = project.segments.map { it.toDto() },
                                warning = Llc.segmentCountWarning(project)
                            )
                        )
                    }
                    .onFailure {
                        call.respond(
                            HttpStatusCode.UnprocessableEntity,
                            ErrorDto("could not read ${file.name}", it.message)
                        )
                    }
            }

            put("/api/media/{id}/project") {
                val entry = index.find(call.parameters["id"].orEmpty())
                    ?: return@put call.respond(HttpStatusCode.NotFound, ErrorDto("unknown media id"))
                val body = call.receive<ProjectDto>()
                val project = CutProject(
                    mediaFileName = entry.fileName,
                    segments = body.segments.map { it.toModel() }
                )
                val target = cuts.writeLlc(
                    config.outputDir,
                    "${entry.fileName.removeSuffix(".mp4")}-proj.llc",
                    Llc.write(project)
                )
                call.respond(ProjectDto(entry.id, entry.fileName, body.segments, "saved to ${target.name}"))
            }

            // ---- export ----

            post("/api/export") {
                val request = call.receive<ExportRequestDto>()
                val outDir = request.outputDir?.takeIf { it.isNotBlank() }?.let(::File) ?: config.outputDir
                if (!outDir.isDirectory && !outDir.mkdirs()) {
                    return@post call.respond(
                        HttpStatusCode.UnprocessableEntity,
                        ErrorDto("输出目录不可用: ${outDir.absolutePath}")
                    )
                }
                val free = outDir.usableSpace
                if (free in 1 until config.minFreeBytes) {
                    return@post call.respond(
                        HttpStatusCode.InsufficientStorage,
                        ErrorDto("磁盘剩余空间不足：${free / 1_073_741_824} GB")
                    )
                }
                val requests = request.items.mapNotNull { item ->
                    val entry = index.find(item.mediaId) ?: return@mapNotNull null
                    ExportRequest(
                        mediaId = entry.id,
                        sourceName = entry.fileName,
                        segments = item.segments.map { it.toModel() }
                    )
                }
                if (requests.isEmpty()) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("没有可导出的片段"))
                }
                val job = exports.submit(requests, outDir, snapMode(request.snap))
                call.respond(job.toDto())
            }

            get("/api/export") {
                call.respond(exports.list().map { it.toDto() })
            }

            get("/api/export/{jobId}") {
                val job = exports.get(call.parameters["jobId"].orEmpty())
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("unknown job"))
                call.respond(job.toDto())
            }

            delete("/api/export/{jobId}") {
                val id = call.parameters["jobId"].orEmpty()
                call.respond(CancelDto(exports.cancel(id)))
            }
        }
    }

    /**
     * Event offset in seconds, bounded to the range the editor offers.
     *
     * Clamped here as well as in the UI so the bound holds for any caller, and so a
     * wild value cannot silently shift every event out of the recording.
     *
     * Omitting the parameter means "use the calibrated default" rather than "no shift":
     * a client that does not know about the offset must still get lanes that line up.
     */
    private fun eventNudge(call: io.ktor.server.application.ApplicationCall): Double =
        (call.request.queryParameters["nudge"]?.toDoubleOrNull() ?: DEFAULT_EVENT_NUDGE_SECONDS)
            .coerceIn(EVENT_OFFSET_MIN, EVENT_OFFSET_MAX)

    /**
     * Null when recordings under the media roots can be unlinked, or the first blocking
     * reason found. Only used to enable/disable the UI's delete controls.
     *
     * Probed per request rather than cached: the mount can be switched from `ro` to `rw`
     * without restarting the container, and a cached "blocked" would keep those controls
     * disabled after the operator had already fixed it.
     */
    private fun deleteProbe(): MediaDeleter.Failure? =
        config.mediaRoots.asSequence()
            .mapNotNull { deleter.probeWritable(it) }
            .firstOrNull()

    private fun proxyHeight(call: io.ktor.server.application.ApplicationCall): Int =
        (call.request.queryParameters["h"]?.toIntOrNull() ?: ProxyService.DEFAULT_HEIGHT).coerceIn(240, 720)

    /** `f=0` keeps every frame; otherwise frames are dropped to this rate. */
    private fun proxyFps(call: io.ktor.server.application.ApplicationCall): Int {
        val raw = call.request.queryParameters["f"]?.toIntOrNull() ?: ProxyService.DEFAULT_FPS
        // Floor of 2, not 1. At 1 fps NVENC's VBR rate control degenerates and emits
        // ~90 KB per frame: measured head-to-head at 360p it produced 1903 MB where
        // 2 fps produced 137 MB. Pinning the GOP did not help, so this is the encoder
        // rather than the keyframe interval, and the setting is simply not offered.
        return if (raw <= 0) 0 else raw.coerceIn(2, 30)
    }

    private fun projectFile(sourceFileName: String): File =
        File(config.outputDir, "${sourceFileName.removeSuffix(".mp4")}-proj.llc")

    /**
     * Preferences come from the query string so the UI can recompute suggestions live
     * while the user drags a threshold slider.
     */
    private fun prefsFrom(params: io.ktor.http.Parameters): SuggestPrefs {
        val preset = params["preset"]?.let { SuggestPrefs.PRESETS[it] }
        val base = preset ?: SuggestPrefs()
        fun d(key: String, fallback: Double) = params[key]?.toDoubleOrNull() ?: fallback
        fun i(key: String, fallback: Int) = params[key]?.toIntOrNull() ?: fallback
        return base.copy(
            padBefore = d("padBefore", base.padBefore),
            padAfter = d("padAfter", base.padAfter),
            mergeGap = d("mergeGap", base.mergeGap),
            minBlock = d("minBlock", base.minBlock),
            absorbGap = d("absorbGap", base.absorbGap),
            maxBlock = d("maxBlock", base.maxBlock),
            minScore = d("minScore", base.minScore),
            maxSuggestions = i("max", base.maxSuggestions),
            weightToy = d("wToy", base.weightToy),
            weightTip = d("wTip", base.weightTip),
            weightSpecial = d("wSpecial", base.weightSpecial),
            weightShow = d("wShow", base.weightShow),
            weightChat = d("wChat", base.weightChat),
            toyOnly = params["toyOnly"]?.toBooleanStrictOrNull() ?: base.toyOnly
            , scoreToyTips = params["scoreToyTips"]?.toBooleanStrictOrNull() ?: base.scoreToyTips
        )
    }

    private fun snapMode(raw: String?): SnapMode = when (raw?.lowercase()) {
        "nearest" -> SnapMode.NEAREST
        "after" -> SnapMode.AFTER
        else -> SnapMode.BEFORE
    }

    private fun SegmentDto.toModel() =
        github.rikacelery.cutter.project.CutSegment(start, end, name, tags, selected)

    private fun github.rikacelery.cutter.project.CutSegment.toDto() =
        SegmentDto(start, end, name, tags, selected)

    private fun github.rikacelery.cutter.ffmpeg.ProxyState.toDto() =
        ProxyDto(mediaId, status.name, height, progress, bytes, error)

    private fun ExportJob.toDto() = ExportJobDto(
        id = id,
        state = state.name,
        outputDir = outputDir,
        total = items.size,
        completed = completed,
        failed = failed,
        startedAt = startedAt,
        finishedAt = finishedAt,
        items = items.map {
            ExportItemDto(
                index = it.index,
                sourceName = it.sourceName,
                requestedStart = it.requestedStart,
                requestedEnd = it.requestedEnd,
                actualStart = it.actualStart,
                snapDelta = it.snapDelta,
                outputName = it.outputName,
                state = it.state.name,
                error = it.error,
                bytes = it.bytes
            )
        },
        log = log
    )

    /** Shared plumbing for the two audio image endpoints. */
    private suspend fun io.ktor.server.application.ApplicationCall.audioLaneImage(
        entry: Pair<MediaEntry, java.io.File>,
        spectrogram: Boolean
    ): java.io.File? {
        val (media, source) = entry
        val probe = runCatching { ProbeCache.probe(config, media.id, source) }.getOrNull()
        if (probe != null && !probe.hasAudio) {
            respond(HttpStatusCode.UnprocessableEntity, ErrorDto("no audio stream"))
            return null
        }
        val from = request.queryParameters["from"]?.toDoubleOrNull()
        val to = request.queryParameters["to"]?.toDoubleOrNull()
        val width = request.queryParameters["w"]?.toIntOrNull()
        val height = request.queryParameters["h"]?.toIntOrNull()

        val image = if (from == null || to == null) {
            val duration = probe?.durationSeconds ?: media.nameDurationSeconds?.toDouble()
            if (duration == null || duration <= 0) {
                respond(HttpStatusCode.UnprocessableEntity, ErrorDto("unknown duration"))
                return null
            }
            if (spectrogram) {
                audio.spectrogram(media, source, 0.0, duration, width ?: 2048, height ?: 256)
            } else {
                audio.overview(media, source, duration)
            }
        } else {
            if (spectrogram) {
                audio.spectrogram(media, source, from, to, width ?: 1024, height ?: 256)
            } else {
                audio.window(media, source, from, to, width ?: 2000)
            }
        }
        if (image == null) {
            respond(HttpStatusCode.InternalServerError, ErrorDto("audio lane unavailable"))
        }
        return image
    }

    /** Resolves an id to the index entry plus its file, responding 404/410 when absent. */
    private suspend fun resolved(id: String): Pair<MediaEntry, java.io.File>? {
        val entry = index.find(id) ?: return null
        val file = index.resolve(entry) ?: return null
        return entry to file
    }

    private suspend fun io.ktor.server.application.ApplicationCall.respondNotFound() {
        respond(HttpStatusCode.NotFound, ErrorDto("unknown media id or file missing"))
    }

    private fun MediaEntry.toDto() = LibraryItemDto(
        id = id,
        relPath = relPath,
        fileName = fileName,
        room = room,
        sizeBytes = sizeBytes,
        modifiedAt = modifiedAt,
        durationSeconds = nameDurationSeconds?.toDouble(),
        startEpochSeconds = startEpochSeconds,
        hasEvents = hasEvents,
        heat = heat
    )

    private fun ParsedEvent.toDto() = EventDto(
        t = mediaSeconds,
        kind = kind.name,
        confidence = confidence.name,
        user = user,
        text = text,
        amount = amount,
        source = tipSource,
        trigger = triggerType,
        action = toyAction,
        seconds = toySeconds,
        power = toyPower.ordinalLevel,
        goal = goal?.goal,
        spent = goal?.spent,
        goalText = goal?.description
    )

    private fun MediaEntry.toLanes(timeline: Timeline, nudge: Double) = LanesDto(
        mediaId = id,
        durationSeconds = nameDurationSeconds?.toDouble(),
        nudgeSeconds = nudge,
        toyIntervals = timeline.toyIntervals.map {
            ToyIntervalDto(
                start = it.startSeconds,
                end = it.endSeconds,
                power = it.power.ordinalLevel,
                action = it.action,
                user = it.user,
                amount = it.amount
            )
        },
        toyLevels = timeline.levelSegments.take(MAX_LEVEL_SEGMENTS).map {
            ToyIntervalDto(
                start = it.startSeconds,
                end = it.endSeconds,
                power = it.power.ordinalLevel,
                action = it.action,
                user = it.user,
                amount = it.amount
            )
        },
        toySpecials = timeline.toySpecials.map {
            SpecialDto(it.seconds, it.action, it.user, it.amount)
        },
        gifts = timeline.gifts.map { GiftDto(it.seconds, it.amount, it.source, it.user) },
        goals = timeline.goals.map { GoalPointDto(it.seconds, it.goal, it.spent, it.description) },
        chats = timeline.chats,
        events = timeline.events.map { it.toDto() },
        stats = StatsDto(
            toyCommandCount = timeline.stats.toyCommandCount,
            toySecondsSum = timeline.stats.toySecondsSum,
            toyBusySeconds = timeline.stats.toyBusySeconds,
            specialCommandCount = timeline.stats.specialCommandCount,
            tipCount = timeline.stats.tipCount,
            tipTotal = timeline.stats.tipTotal,
            chatCount = timeline.stats.chatCount,
            goalCount = timeline.stats.goalCount,
            showCount = timeline.stats.showCount,
            maxPower = timeline.stats.maxPower.ordinalLevel,
            withOwnTimestamp = timeline.stats.withOwnTimestamp,
            confidence = timeline.stats.confidence.name
        )
    )

    private suspend fun MediaEntry.toDetail(file: java.io.File): MediaDetailDto {
        val probe = runCatching { ProbeCache.probe(config, id, file) }.getOrNull()
        return MediaDetailDto(
            id = id,
            fileName = fileName,
            relPath = relPath,
            room = room,
            sizeBytes = sizeBytes,
            durationSeconds = probe?.durationSeconds ?: nameDurationSeconds?.toDouble(),
            startEpochSeconds = startEpochSeconds,
            hasEvents = index.resolveEvent(this) != null,
            streams = probe?.streams?.map { s ->
                StreamDto(
                    index = s.index,
                    type = s.type,
                    codec = s.codec,
                    width = s.width,
                    height = s.height,
                    sampleRate = s.sampleRate,
                    channels = s.channels,
                    fps = s.fps
                )
            } ?: emptyList(),
            nameDurationSeconds = nameDurationSeconds
        )
    }

    companion object {
        const val VERSION = "xhcut-0.1.0"

        /**
         * Upper bound on intensity segments sent to the browser. Real recordings sit
         * around 6.5k after equal-power fusion; this only guards pathological files.
         */
        private const val MAX_LEVEL_SEGMENTS = 30_000

        /** Event offset bounds, mirroring the editor's slider. */
        const val EVENT_OFFSET_MIN = -3.0
        const val EVENT_OFFSET_MAX = 17.0

        /** Widest span accepted by the keyframe probe; see the route comment. */
        private const val MAX_KEYFRAME_WINDOW = 1800.0
    }
}

@Serializable
data class ConfigDto(
    val zone: String,
    val previewSegmentSeconds: Int,
    /**
     * Whether recordings can be removed at all. False when the media root is mounted
     * read-only, which is the shipped default — the UI disables its delete controls
     * instead of offering an action that can only fail.
     */
    val deleteEnabled: Boolean = false,
    /** Human-readable cause when [deleteEnabled] is false. */
    val deleteBlockedReason: String? = null
)

@Serializable
data class SettingsDto(
    val deleteSourceAfterExport: Boolean,
    /** Mirrors `/api/config`: without a writable media root this setting cannot act. */
    val deleteEnabled: Boolean,
    val error: String? = null
)

@Serializable
data class SettingsUpdateDto(val deleteSourceAfterExport: Boolean)

/** Outcome of a successful delete: the paths that are now gone. */
@Serializable
data class DeleteResultDto(
    val fileName: String,
    val deleted: List<String>
)

@Serializable
data class StreamDto(
    val index: Int,
    val type: String,
    val codec: String,
    val width: Int? = null,
    val height: Int? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val fps: Double? = null
)

@Serializable
data class MediaDetailDto(
    val id: String,
    val fileName: String,
    val relPath: String,
    val room: String,
    val sizeBytes: Long,
    val durationSeconds: Double?,
    val startEpochSeconds: Long?,
    val nameDurationSeconds: Long?,
    /**
     * Whether the recording has an `.event` sidecar.
     *
     * A boolean rather than a server path: the client only ever asks yes/no, and an
     * absolute path would hand the host's filesystem layout to the browser.
     */
    val hasEvents: Boolean,
    val streams: List<StreamDto>
)
