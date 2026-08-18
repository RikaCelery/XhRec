package github.rikacelery.v3.postprocessors

import github.rikacelery.v3.utils.runProcessStreaming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class FixStampProcessor(private val destinationFolder: File) : Processor() {
    override suspend fun process(input: File, ctx: ProcessorCtx): List<File> {
        withContext(Dispatchers.IO) { destinationFolder.mkdirs() }
        val output = File(destinationFolder, input.name.replace(".mp4", ".fixed.mp4"))
        runProcessStreaming(
            { line -> logger.info("[ffmpeg] {}", line) },
            "ffmpeg",
            "-hide_banner",
            "-loglevel", "error",
            "-i", input.absolutePath, "-c", "copy",
            output.absolutePath
        )
        withContext(Dispatchers.IO) { input.delete() }

        val eventFile = input.parentFile.resolve(input.name + ".event")
        if (eventFile.exists()) {
            val destEvent = File(destinationFolder, output.name + ".event")
            withContext(Dispatchers.IO) {
                eventFile.copyTo(destEvent, overwrite = true)
                eventFile.delete()
            }
        }
        return listOf(output)
    }
}
