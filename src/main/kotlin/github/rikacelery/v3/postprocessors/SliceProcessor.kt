package github.rikacelery.v3.postprocessors

import github.rikacelery.utils.runProcessGetStdout
import java.io.File
import java.time.Duration

class SliceProcessor(
    private val sliceDuration: Duration,
    private val destinationFolder: File
) : Processor() {
    override suspend fun process(input: File, ctx: ProcessorCtx): List<File> {
        val base = File(destinationFolder, input.nameWithoutExtension)
        base.mkdirs()
        runProcessGetStdout("ffmpeg", "-i", input.absolutePath, "-c", "copy",
            "-f", "segment", "-segment_time", sliceDuration.seconds.toString(),
            "-reset_timestamps", "1",
            File(base, "part_%03d.mp4").absolutePath)
        return base.listFiles()?.toList() ?: emptyList()
    }
}
