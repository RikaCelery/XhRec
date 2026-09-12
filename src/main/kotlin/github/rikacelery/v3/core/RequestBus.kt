package github.rikacelery.v3.core

import github.rikacelery.v3.events.CommandAck
import github.rikacelery.v3.events.CommandEnvelope
import github.rikacelery.v3.events.ErrorResponse
import github.rikacelery.v3.events.Request
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class RequestTimeoutException(cmd: Request, timeoutMs: Long) :
    RuntimeException("Request $cmd timed out after ${timeoutMs}ms")

class RequestErrorException(cmd: Request, message: String) :
    RuntimeException("Request $cmd failed: $message")

class RequestBus(
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val defaultTimeoutMs: Long = 5000
) {
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Any>>()
    private val idGen = AtomicLong(0)
    private val logger = LoggerFactory.getLogger("v3.RequestBus")

    // The ack collector attaches asynchronously and the bus does not replay, so a caller that can
    // race with startup should await this before issuing its first request.
    private val ackAttached = CompletableDeferred<Unit>()

    init {
        eventBus.subscribe(scope, CommandAck::class, onAttached = { ackAttached.complete(Unit) }) { ack ->
            pending.remove(ack.requestId)?.complete(ack.body)
        }
    }

    /** Suspends until this bus is collecting [CommandAck]s, so no reply can be missed. */
    suspend fun awaitSubscribed(timeout: Duration = 10.seconds): Boolean =
        withTimeoutOrNull(timeout) { ackAttached.await() } != null

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> request(cmd: Request, timeoutMs: Long = defaultTimeoutMs): T {
        val id = idGen.incrementAndGet()
        val deferred = CompletableDeferred<Any>(parent = currentCoroutineContext().job)
        pending[id] = deferred
        val startedAt = System.currentTimeMillis()

        eventBus.publish(CommandEnvelope(id, cmd))

        return try {
            val result = withTimeout(timeoutMs) { deferred.await() }
            if (result is ErrorResponse) {
                monitor(id, cmd, startedAt, "error: ${result.message}")
                throw RequestErrorException(cmd, result.message)
            }
            monitor(id, cmd, startedAt, result)
            result as T
        } catch (e: TimeoutCancellationException) {
            logger.error("Request timeout: cmd=$cmd, timeout=${timeoutMs}ms", e)
            pending.remove(id)?.cancel()
            monitor(id, cmd, startedAt, "TIMEOUT after ${timeoutMs}ms (no component answered)")
            throw RequestTimeoutException(cmd, timeoutMs)
        }
    }

    /** Feeds one round trip to the debug stream; a no-op unless a client is watching. */
    private fun monitor(id: Long, cmd: Request, startedAt: Long, outcome: Any?) {
        if (!BusMonitor.wants(BusMonitor.REQUEST)) return
        BusMonitor.record(BusMonitor.REQUEST, buildJsonObject {
            put("id", id)
            put("cmd", BusMonitor.shorten(cmd))
            put("ms", System.currentTimeMillis() - startedAt)
            put("result", BusMonitor.shorten(outcome))
        })
    }
}