package github.rikacelery.v3.postprocessors

import github.rikacelery.v3.utils.runProcessStreaming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration

class SliceProcessor(
    private val sliceDuration: Duration,
    private val destinationFolder: File
) : Processor() {
    override suspend fun process(input: File, ctx: ProcessorCtx): List<File> {
        val base = File(destinationFolder, input.nameWithoutExtension)
        withContext(Dispatchers.IO) { base.mkdirs() }
        runProcessStreaming(
            { line -> logger.info("[ffmpeg] {}", line) },
            "ffmpeg", "-hide_banner", "-v", "error", "-i", input.absolutePath, "-c", "copy",
            "-f", "segment", "-segment_time", sliceDuration.seconds.toString(),
            "-reset_timestamps", "1",
            File(base, "part_%03d.mp4").absolutePath
        )
        val parts = withContext(Dispatchers.IO) {
            input.delete()
            base.listFiles()?.toList() ?: emptyList()
        }
        return parts
    }
}
