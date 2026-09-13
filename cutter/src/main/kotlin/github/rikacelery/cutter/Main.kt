package github.rikacelery.cutter

import github.rikacelery.cutter.config.CutConfig
import github.rikacelery.cutter.config.MissingOptionException
import github.rikacelery.cutter.config.SettingsStore
import github.rikacelery.cutter.events.TimelineService
import github.rikacelery.cutter.ffmpeg.AudioLanes
import github.rikacelery.cutter.ffmpeg.CutEngine
import github.rikacelery.cutter.ffmpeg.ExportQueue
import github.rikacelery.cutter.ffmpeg.PreviewServer
import github.rikacelery.cutter.ffmpeg.ProxyService
import github.rikacelery.cutter.ffmpeg.Proc
import github.rikacelery.cutter.media.MediaDeleter
import github.rikacelery.cutter.media.MediaIndex
import github.rikacelery.cutter.web.CutServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("cutter.Main")

fun main(args: Array<String>) {
    val config = try {
        CutConfig.parse(args)
    } catch (e: MissingOptionException) {
        // A configuration mistake is a usage error, not a crash: say what is missing and
        // exit with a distinct code so a supervisor can tell it apart from a runtime fault.
        System.err.println("xhcut: ${e.message}")
        kotlin.system.exitProcess(2)
    }

    log.info("XhCut starting: port={} media={} out={} cache={} zone={}",
        config.port,
        config.mediaRoots.joinToString(",") { it.absolutePath },
        config.outputDir.absolutePath,
        config.cacheDir.absolutePath,
        config.zone.id)

    Proc.ensureDir(config.cacheDir)
    Proc.ensureDir(config.outputDir)

    val ffmpeg = Proc.probeTool(config.ffmpeg)
    val ffprobe = Proc.probeTool(config.ffprobe)
    if (ffmpeg == null) log.error("ffmpeg not usable: {}", config.ffmpeg) else log.info("ffmpeg: {}", ffmpeg)
    if (ffprobe == null) log.error("ffprobe not usable: {}", config.ffprobe) else log.info("ffprobe: {}", ffprobe)

    val index = MediaIndex(config)
    val timelines = TimelineService(config, index)
    val previews = PreviewServer(config, index)
    val audio = AudioLanes(config)
    val cuts = CutEngine(config)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val proxies = ProxyService(config, scope)
    val deleter = MediaDeleter(config, index, previews, audio, proxies, timelines)
    // Instance-wide settings live beside the index in the cache dir.
    val settings = SettingsStore(File(config.cacheDir, "settings.json"))
    // Exports take the deleter so "delete the source once it is fully exported" can act.
    val exports = ExportQueue(config, index, cuts, scope, settings, deleter)

    // Serve the cached index immediately, then refresh in the background so a cold
    // start is not blocked behind a full walk of the media tree.
    runBlocking {
        runCatching { index.loadCache() }
            .onFailure { log.warn("index cache load failed: {}", it.message) }
        runCatching { settings.load() }
            .onFailure { log.warn("settings load failed: {}", it.message) }
    }
    scope.launch {
        runCatching { index.scan() }
            .onFailure { log.error("initial scan failed", it) }
        // Rank the library by toy activity once the tree is known.
        runCatching { timelines.enrichAll(index.entries) }
            .onFailure { log.error("heat enrichment failed", it) }
    }

    Runtime.getRuntime().addShutdownHook(Thread { log.info("XhCut shutting down") })

    CutServer(config, index, timelines, previews, proxies, audio, cuts, exports, deleter, settings, scope).start(wait = true)
}
