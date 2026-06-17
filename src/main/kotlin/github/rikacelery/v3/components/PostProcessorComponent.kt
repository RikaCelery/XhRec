package github.rikacelery.v3.components

import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.events.FileProcessed
import github.rikacelery.v3.events.FileReady
import github.rikacelery.v3.events.RecordingStopped
import github.rikacelery.v3.postprocessors.Processor
import github.rikacelery.v3.postprocessors.ProcessorCtx
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.*
import java.util.concurrent.ConcurrentHashMap

sealed interface PostProcessorMsg
data class OnProcessorEvent(val event: Any) : PostProcessorMsg

class PostProcessorComponent(
    eventBus: EventBus,
    parentScope: CoroutineScope,
    maxConcurrency: Int = 4
) : Actor<PostProcessorMsg>("PostProcessorComponent", eventBus, parentScope) {

    private var processors: List<Processor> = emptyList()
    private val semaphore = Semaphore(maxConcurrency)
    private val rooms = ConcurrentHashMap<Long, RoomProcessor>()
    val jobs = Hashtable<String, Job>()

    private inner class RoomProcessor {
        val channel = Channel<FileReady>(capacity = 8)
        var job: Job? = null
    }

    fun setProcessors(procs: List<Processor>) {
        processors = procs
    }

    override suspend fun onStart(scope: CoroutineScope) {
        subscribe<FileReady>(FileReady::class)
        subscribe<RecordingStopped>(RecordingStopped::class)
    }

    override suspend fun wrapEvent(event: Any): PostProcessorMsg? = when (event) {
        is FileReady -> OnProcessorEvent(event)
        is RecordingStopped -> OnProcessorEvent(event)
        else -> null
    }

    override suspend fun handle(msg: PostProcessorMsg) {
        when (msg) {
            is OnProcessorEvent -> when (val e = msg.event) {
                is FileReady -> {
                    val rp = rooms.getOrPut(e.roomId) { RoomProcessor() }
                    // ensure the consumer coroutine is running
                    if (rp.job == null || rp.job?.isCompleted == true) {
                        rp.job = scope.launch {
                            for (event in rp.channel) {
                                val trackJob = scope.launch {
                                    semaphore.withPermit {
                                        try {
                                            logger.info("processing {}", event.file)
                                            processFile(event)
                                            logger.info("process ok {}", event.file)
                                        } catch (ex: CancellationException) {
                                            throw ex
                                        } catch (ex: Exception) {
                                            logger.error("processFile failed: {}", ex.message, ex)
                                        }
                                    }
                                }
                                val key = event.file.toString()
                                jobs[key] = trackJob
                                trackJob.invokeOnCompletion { jobs.remove(key) }
                                trackJob.join()
                            }
                        }
                    }
                    // send directly — this is a suspend call in handle(), so it only
                    // blocks this actor's mailbox (not EventBus). other rooms are unaffected
                    // because FileReady/RecordingStopped for different rooms dispatch immediately.
                    rp.channel.send(e)
                }

                is RecordingStopped -> {
                    val rp = rooms.remove(e.roomId) ?: return
                    rp.channel.close()
                    rp.job?.join()
                }

                else -> {}
            }
        }
    }

    private suspend fun processFile(event: FileReady) {
        var files = listOf(event.file)
        for (processor in processors) {
            val processorName = processor::class.simpleName ?: "?"
            files = files.flatMap { f ->
                try {
                    logger.info("[{}] running {}", processorName, f.name)
                    val ctx = ProcessorCtx(
                        roomId = event.roomId, roomName = event.roomName,
                        startTime = event.startTime, endTime = event.endTime,
                        durationMs = event.durationMs, quality = event.quality
                    )
                    processor.process(f, ctx)
                } catch (e: Exception) {
                    logger.error("[{}] error: {}", processorName, e.message)
                    listOf(f)
                }
            }
        }
        eventBus.publish(FileProcessed(event.roomId, files.lastOrNull() ?: event.file))
    }
}
