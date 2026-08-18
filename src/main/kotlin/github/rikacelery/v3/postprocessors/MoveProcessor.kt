package github.rikacelery.v3.postprocessors

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MoveProcessor(
    private val template: String,
    datePattern: String,
) : Processor() {
    private val fmt = DateTimeFormatter.ofPattern(datePattern).withZone(ZoneId.systemDefault())

    override suspend fun process(input: File, ctx: ProcessorCtx): List<File> {
        val needsFrames = "{{TOTAL_FRAMES}}" in template
        val needsFramesGuess = "{{TOTAL_FRAMES_GUESS}}" in template
        val replace: suspend (String) -> String = { arg ->
            var s = arg.replace("{{ROOM_NAME}}", ctx.roomName)
                .replace("{{ROOM_ID}}", ctx.roomId.toString())
                .replace("{{RECORD_START}}", fmt.format(Instant.ofEpochMilli(ctx.startTime)))
                .replace("{{RECORD_END}}", fmt.format(Instant.ofEpochMilli(ctx.endTime)))
                .replace("{{RECORD_DURATION}}", (ctx.durationMs / 1000).toString())
                .replace("{{RECORD_DURATION_STR}}", formatDuration(ctx.durationMs))
                .replace("{{RECORD_QUALITY}}", ctx.quality)
                .replace("{{INPUT_ABS}}", input.absolutePath)
                .replace("{{INPUT_DIR}}", input.absoluteFile.parent)
                .replace("{{INPUT_NAME}}", input.name)
                .replace("{{INPUT_NAME_NOEXT}}", input.nameWithoutExtension)
            if (needsFrames) s = s.replace("{{TOTAL_FRAMES}}", totalFrames(input))
            if (needsFramesGuess) s = s.replace("{{TOTAL_FRAMES_GUESS}}", totalFramesGuess(input, ctx.durationMs))
            s
        }
        val resolved = replace(template)
        val isDir = File(resolved).extension.isEmpty()
        val dest = if (isDir) File(resolved, input.name) else File(resolved)

        // file copies/deletes are blocking IO (possibly multi-GB) — keep them off the actor thread
        withContext(Dispatchers.IO) {
            dest.parentFile.mkdirs()
            val eventFile = input.parentFile.resolve(input.name + ".event")
            if (eventFile.exists()) {
                val destEvent = dest.parentFile.resolve(dest.name + ".event")
                eventFile.copyTo(destEvent, overwrite = true)
                eventFile.delete()
            }
            if (input.absolutePath != dest.absolutePath) {
                input.copyTo(dest, overwrite = true)
                input.delete()
            }
        }
        return listOf(dest)
    }

    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        return if (days > 0) {
            "%02dd%02dh%02dm%02ds".format(days, hours % 24, minutes % 60, seconds % 60)
        } else {
            "%02dh%02dm%02ds".format(hours % 24, minutes % 60, seconds % 60)
        }
    }

    private suspend fun totalFrames(input: File): String = withContext(Dispatchers.IO) {
        runCatching {
            ProcessBuilder(
                "ffprobe", "-v", "error", "-select_streams", "v:0",
                "-count_frames", "-show_entries", "stream=nb_frames",
                "-of", "default=noprint_wrappers=1:nokey=1", input.absolutePath
            ).start().inputStream.bufferedReader().readText().trim()
        }.getOrElse { "" }
    }

    private suspend fun totalFramesGuess(input: File, durationMs: Long): String = withContext(Dispatchers.IO) {
        runCatching {
            val fpsStr = ProcessBuilder(
                "ffprobe", "-v", "error", "-select_streams", "v:0",
                "-show_entries", "stream=r_frame_rate",
                "-of", "default=noprint_wrappers=1:nokey=1", input.absolutePath
            ).start().inputStream.bufferedReader().readText().trim()
            val fps = fpsStr.split("/").mapNotNull { it.toLongOrNull() }
                .takeIf { it.size == 2 }?.let { it[0].toDouble() / it[1] } ?: return@withContext ""
            (fps * durationMs / 1000).toLong().toString()
        }.getOrElse { "" }
    }
}
