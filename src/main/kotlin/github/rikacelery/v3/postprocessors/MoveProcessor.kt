package github.rikacelery.v3.postprocessors

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MoveProcessor(
    private val template: String,
    private val destinationFolder: File
) : Processor() {
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss").withZone(ZoneId.systemDefault())

    override suspend fun process(input: File, ctx: ProcessorCtx): List<File> {
        val startStr = fmt.format(Instant.ofEpochMilli(ctx.startTime))
        val endStr = fmt.format(Instant.ofEpochMilli(ctx.endTime))
        val path = template
            .replace("{{ROOM_NAME}}", ctx.roomName)
            .replace("{{ROOM_ID}}", ctx.roomId.toString())
            .replace("{{RECORD_START}}", startStr)
            .replace("{{RECORD_END}}", endStr)
            .replace("{{RECORD_DURATION_STR}}", formatDuration(ctx.durationMs))
        val dir = File(destinationFolder, path)
        dir.mkdirs()
        val dest = File(dir, input.name)
        input.copyTo(dest, overwrite = true)
        return listOf(dest)
    }

    private fun formatDuration(ms: Long): String {
        val h = ms / 3600_000
        val m = (ms % 3600_000) / 60_000
        val s = (ms % 60_000) / 1000
        return "${h}h${m}m${s}s"
    }
}
