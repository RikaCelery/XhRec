package github.rikacelery.v3.postprocessors

import github.rikacelery.utils.runProcessGetStdout
import java.io.File

class FixStampProcessor(private val destinationFolder: File) : Processor() {
    override suspend fun process(input: File, ctx: ProcessorCtx): List<File> {
        val output = File(destinationFolder, input.name.replace(".mp4", "-fixed.mp4"))
        runProcessGetStdout("ffmpeg", "-i", input.absolutePath, "-c", "copy",
            "-movflags", "+faststart", output.absolutePath)
        return listOf(output)
    }
}
