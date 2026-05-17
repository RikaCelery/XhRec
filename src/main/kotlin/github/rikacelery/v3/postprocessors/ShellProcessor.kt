package github.rikacelery.v3.postprocessors

import github.rikacelery.utils.runProcessGetStdout
import java.io.File

class ShellProcessor(
    private val command: List<String>,
    private val noreturn: Boolean = false,
    private val removeInput: Boolean = false
) : Processor() {
    override suspend fun process(input: File, ctx: ProcessorCtx): List<File> {
        val cmd = command.map { substitute(it, ctx, input) }
        runProcessGetStdout(*cmd.toTypedArray())
        if (removeInput) input.delete()
        return if (noreturn) listOf(input) else listOf(input)
    }

    private fun substitute(template: String, ctx: ProcessorCtx, file: File): String {
        return template
            .replace("{{INPUT}}", file.absolutePath)
            .replace("{{ROOM_NAME}}", ctx.roomName)
            .replace("{{ROOM_ID}}", ctx.roomId.toString())
    }
}
