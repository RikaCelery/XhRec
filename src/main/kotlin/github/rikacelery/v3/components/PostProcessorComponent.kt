package github.rikacelery.v3.components

import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.events.*
import github.rikacelery.v3.postprocessors.Processor
import github.rikacelery.v3.postprocessors.ProcessorCtx
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

sealed interface PostProcessorMsg
data class OnProcessorEvent(val event: Any) : PostProcessorMsg

class PostProcessorComponent(
    eventBus: EventBus,
    parentScope: CoroutineScope,
    maxConcurrency: Int = 4
) : Actor<PostProcessorMsg>("PostProcessorComponent", eventBus, parentScope) {

    private var processors: List<Processor> = emptyList()
    private val semaphore = Semaphore(maxConcurrency)

    fun setProcessors(procs: List<Processor>) {
        processors = procs
    }

    override suspend fun onStart(scope: CoroutineScope) {
        subscribe<FileReady>(FileReady::class)
    }

    override suspend fun wrapEvent(event: Any): PostProcessorMsg? = when (event) {
        is FileReady -> OnProcessorEvent(event)
        else -> null
    }

    override suspend fun handle(msg: PostProcessorMsg) {
        when (msg) {
            is OnProcessorEvent -> when (val e = msg.event) {
                is FileReady -> {
                    scope.launch {
                        semaphore.withPermit { processFile(e) }
                    }
                }
                else -> {}
            }
        }
    }

    private suspend fun processFile(event: FileReady) {
        var files = listOf(event.file)
        for (processor in processors) {
            files = files.flatMap { f ->
                try {
                    val ctx = ProcessorCtx(
                        roomId = event.roomId, roomName = "unknown",
                        startTime = 0L, endTime = System.currentTimeMillis(),
                        durationMs = 0L, quality = ""
                    )
                    processor.process(f, ctx)
                } catch (e: Exception) {
                    logger.error("Processor error: ${e.message}")
                    listOf(f)
                }
            }
        }
        eventBus.publish(FileProcessed(event.roomId, files.lastOrNull() ?: event.file))
    }
}
