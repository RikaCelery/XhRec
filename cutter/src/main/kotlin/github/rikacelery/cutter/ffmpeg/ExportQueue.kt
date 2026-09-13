package github.rikacelery.cutter.ffmpeg

import github.rikacelery.cutter.config.CutConfig
import github.rikacelery.cutter.config.SettingsStore
import github.rikacelery.cutter.media.MediaDeleter
import github.rikacelery.cutter.media.MediaEntry
import github.rikacelery.cutter.media.MediaIndex
import github.rikacelery.cutter.project.CutProject
import github.rikacelery.cutter.project.CutSegment
import github.rikacelery.cutter.project.Llc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicLong

enum class ExportState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class ExportItem(
    val index: Int,
    val mediaId: String,
    val sourceName: String,
    val requestedStart: Double,
    val requestedEnd: Double,
    val actualStart: Double = 0.0,
    val snapDelta: Double = 0.0,
    val outputName: String = "",
    val state: ExportState = ExportState.QUEUED,
    val error: String? = null,
    val bytes: Long = 0
)

data class ExportJob(
    val id: String,
    val outputDir: String,
    val items: List<ExportItem>,
    val state: ExportState = ExportState.QUEUED,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val log: List<String> = emptyList()
) {
    val completed: Int get() = items.count { it.state == ExportState.DONE }
    val failed: Int get() = items.count { it.state == ExportState.FAILED }
}

/**
 * Serial queue of lossless cut jobs.
 *
 * Export runs one segment at a time. ffmpeg copy cuts are I/O bound and the source
 * lives on a NAS, so running several at once mostly adds seeking contention; a single
 * worker keeps ordering predictable and makes progress reporting honest.
 */
class ExportQueue(
    private val config: CutConfig,
    private val index: MediaIndex,
    private val engine: CutEngine,
    private val scope: CoroutineScope,
    private val settings: SettingsStore? = null,
    private val deleter: MediaDeleter? = null
) {
    private val log = LoggerFactory.getLogger(ExportQueue::class.java)
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, ExportJob>()
    private val running = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val sequence = AtomicLong(0)

    private val _active = MutableStateFlow<ExportJob?>(null)
    val active: StateFlow<ExportJob?> = _active.asStateFlow()

    fun list(): List<ExportJob> = jobs.values.sortedByDescending { it.startedAt }

    fun get(id: String): ExportJob? = jobs[id]

    /** Queue a set of segments for one or more recordings. */
    fun submit(
        requests: List<ExportRequest>,
        outputDir: File = config.outputDir,
        snapMode: SnapMode = SnapMode.BEFORE,
        writeProject: Boolean = true
    ): ExportJob {
        val id = "job-" + sequence.incrementAndGet()
        val items = ArrayList<ExportItem>(requests.size)
        requests.forEachIndexed { i, request ->
            request.segments.forEachIndexed { segIndex, segment ->
                items.add(
                    ExportItem(
                        index = i * 1000 + segIndex,
                        mediaId = request.mediaId,
                        sourceName = request.sourceName,
                        requestedStart = segment.start,
                        requestedEnd = segment.end ?: 0.0
                    )
                )
            }
        }
        val job = ExportJob(id = id, outputDir = outputDir.absolutePath, items = items)
        jobs[id] = job
        _active.value = job

        running[id] = scope.launch(Dispatchers.IO) {
            runJob(job, requests, outputDir, snapMode, writeProject)
        }
        return job
    }

    fun cancel(id: String): Boolean {
        val handle = running.remove(id) ?: return false
        handle.cancel()
        update(id) { it.copy(state = ExportState.CANCELLED, finishedAt = System.currentTimeMillis()) }
        return true
    }

    private suspend fun runJob(
        job: ExportJob,
        requests: List<ExportRequest>,
        outputDir: File,
        snapMode: SnapMode,
        writeProject: Boolean
    ) {
        update(job.id) { it.copy(state = ExportState.RUNNING) }
        appendLog(job.id, "开始导出 ${job.items.size} 个片段到 ${outputDir.absolutePath}")

        val probes = HashMap<String, MediaProbe?>()
        val cutCounters = HashMap<String, Int>()

        for (request in requests) {
            val entry = index.find(request.mediaId)
            if (entry == null) {
                appendLog(job.id, "跳过：找不到 ${request.sourceName}")
                continue
            }
            val source = index.resolve(entry)
            if (source == null) {
                appendLog(job.id, "跳过：文件不存在 ${entry.relPath}")
                continue
            }
            val probe = probes.getOrPut(entry.id) {
                runCatching { ProbeCache.probe(config, entry.id, source) }.getOrNull()
            }
            val hasAudio = probe?.hasAudio ?: true

            // Per-source numbering restarts at 1 and skips names already on disk,
            // matching the existing corpus convention.
            val startNumber = cutCounters.getOrPut(entry.id) { engine.nextCutNumber(outputDir, entry) }
            var number = startNumber

            // Every interval this job actually wrote for the recording, collected so the
            // delete decision below is made against measured coverage rather than intent.
            var producedSeconds = 0.0

            for (segment in request.segments) {
                if (segment.end == null) {
                    appendLog(job.id, "跳过标记（无结束时间）@ ${segment.start}")
                    continue
                }
                val itemIndex = job.items.indexOfFirst {
                    it.mediaId == entry.id && it.requestedStart == segment.start && it.requestedEnd == segment.end
                }
                if (itemIndex < 0) continue
                val item = job.items[itemIndex]

                val planned = runCatching {
                    engine.plan(entry, source, segment.start, segment.end, snapMode, number)
                }.getOrElse {
                    failItem(job.id, itemIndex, it.message ?: "规划失败")
                    continue
                }
                val output = File(outputDir, planned.outputName)
                val duration = planned.actualEnd - planned.actualStart
                if (duration <= 0) {
                    failItem(job.id, itemIndex, "片段长度为零")
                    continue
                }

                updateItem(job.id, itemIndex) {
                    it.copy(
                        state = ExportState.RUNNING,
                        actualStart = planned.actualStart,
                        snapDelta = planned.snapDelta,
                        outputName = planned.outputName
                    )
                }
                val snapNote = if (kotlin.math.abs(planned.snapDelta) > 0.01) {
                    val delta = String.format(java.util.Locale.ROOT, "%.2f", planned.snapDelta)
                    " (关键帧对齐 $delta" + "s)"
                } else {
                    ""
                }
                appendLog(job.id, "导出 ${planned.outputName}$snapNote")

                val command = engine.buildCutCommand(source, output, planned.actualStart, duration, hasAudio)
                val ok = engine.runCut(command) { line -> appendLog(job.id, "  $line") }
                if (ok && output.isFile && output.length() > 0) {
                    updateItem(job.id, itemIndex) { it.copy(state = ExportState.DONE, bytes = output.length()) }
                    producedSeconds += planned.actualEnd - planned.actualStart
                    appendLog(job.id, "完成 ${output.name} (${output.length() / 1_048_576} MB)")
                } else {
                    output.delete()
                    failItem(job.id, itemIndex, "ffmpeg 退出异常")
                }
                number++
            }

            if (writeProject) {
                val project = CutProject(
                    mediaFileName = entry.fileName,
                    segments = request.segments
                )
                runCatching {
                    engine.writeLlc(outputDir, "${entry.fileName.removeSuffix(".mp4")}-proj.llc", Llc.write(project))
                }.onFailure { appendLog(job.id, "项目文件写入失败：${it.message}") }
            }

            maybeDeleteSource(job.id, entry, producedSeconds)
        }

        val failed = jobs[job.id]?.failed ?: 0
        update(job.id) {
            it.copy(
                state = ExportState.DONE,
                finishedAt = System.currentTimeMillis()
            )
        }
        appendLog(job.id, if (failed == 0) "全部完成" else "完成，$failed 个片段失败")
        running.remove(job.id)
        if (_active.value?.id == job.id) _active.value = jobs[job.id]
    }

    /**
     * Removes the source after its segments have been exported.
     *
     * Deliberately *not* conditional on the export covering the whole recording: exporting
     * a highlight and discarding the rest is a legitimate — and intended — use of this
     * setting. The recording is therefore gone once any part of it has been written out.
     *
     * It runs **after** every segment of this recording has been processed, never between
     * them, because the remaining segments of the same job still need the source file to
     * cut from. A job that produced nothing at all is also skipped: that is a failed
     * export, and deleting on failure would destroy the very material the user was trying
     * to salvage.
     */
    private suspend fun maybeDeleteSource(
        jobId: String,
        entry: MediaEntry,
        producedSeconds: Double
    ) {
        val store = settings ?: return
        val remover = deleter ?: return
        if (!store.value.deleteSourceAfterExport) return

        if (producedSeconds <= 0.0) {
            appendLog(jobId, "保留源文件 ${entry.fileName}：本次没有成功导出任何片段")
            return
        }

        val result = remover.delete(entry)
        if (result.ok) {
            appendLog(
                jobId,
                "已删除源文件 ${entry.fileName}（本次导出 " +
                    String.format(java.util.Locale.ROOT, "%.1f", producedSeconds) + "s）"
            )
        } else {
            appendLog(
                jobId,
                "源文件删除失败 ${entry.fileName}：" +
                    result.failed.joinToString("; ") { "${it.path}: ${it.reason}" }
            )
        }
    }

    private fun failItem(jobId: String, itemIndex: Int, reason: String) {
        updateItem(jobId, itemIndex) { it.copy(state = ExportState.FAILED, error = reason) }
        appendLog(jobId, "失败：$reason")
    }

    private fun updateItem(jobId: String, itemIndex: Int, transform: (ExportItem) -> ExportItem) {
        update(jobId) { job ->
            val items = job.items.toMutableList()
            if (itemIndex in items.indices) items[itemIndex] = transform(items[itemIndex])
            job.copy(items = items)
        }
    }

    private fun update(jobId: String, transform: (ExportJob) -> ExportJob) {
        jobs.computeIfPresent(jobId) { _, job -> transform(job) }
        if (_active.value?.id == jobId) _active.value = jobs[jobId]
    }

    private fun appendLog(jobId: String, line: String) {
        log.info("[{}] {}", jobId, line)
        update(jobId) { job ->
            // Keep the tail bounded; a long export can emit thousands of ffmpeg lines.
            val next = job.log + line
            job.copy(log = if (next.size > 500) next.takeLast(500) else next)
        }
    }
}

/** One recording's worth of segments to export. */
data class ExportRequest(
    val mediaId: String,
    val sourceName: String,
    val segments: List<CutSegment>
)
