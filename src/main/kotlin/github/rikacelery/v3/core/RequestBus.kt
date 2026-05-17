package github.rikacelery.v3.core

import github.rikacelery.v3.events.CommandAck
import github.rikacelery.v3.events.CommandEnvelope
import github.rikacelery.v3.events.Request
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class RequestTimeoutException(cmd: Request, timeoutMs: Long) :
    RuntimeException("Request $cmd timed out after ${timeoutMs}ms")

class RequestBus(
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val defaultTimeoutMs: Long = 5000
) {
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Any>>()
    private val idGen = AtomicLong(0)

    init {
        eventBus.subscribe(scope, CommandAck::class) { ack ->
            pending.remove(ack.requestId)?.complete(ack.body)
        }
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> request(cmd: Request, timeoutMs: Long = defaultTimeoutMs): T {
        val id = idGen.incrementAndGet()
        val deferred = CompletableDeferred<Any>(parent = currentCoroutineContext().job)
        pending[id] = deferred

        eventBus.publish(CommandEnvelope(id, cmd))

        return try {
            withTimeout(timeoutMs) { deferred.await() as T }
        } catch (e: TimeoutCancellationException) {
            pending.remove(id)
            throw RequestTimeoutException(cmd, timeoutMs)
        }
    }
}