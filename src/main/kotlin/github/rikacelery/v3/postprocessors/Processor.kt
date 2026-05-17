package github.rikacelery.v3.postprocessors

import java.io.File

abstract class Processor {
    abstract suspend fun process(input: File, ctx: ProcessorCtx): List<File>
}
