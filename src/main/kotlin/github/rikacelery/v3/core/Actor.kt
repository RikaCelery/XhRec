package github.rikacelery.v3.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

abstract class Actor<T : Any>(
    val name: String,
    protected val eventBus: EventBus,
    parentScope: CoroutineScope,
    mailboxCapacity: Int = 256
) {
    private val mailbox = Channel<T>(capacity = mailboxCapacity)
    protected val scope = parentScope + SupervisorJob() + CoroutineName(name)
    protected val logger = LoggerFactory.getLogger("v3.$name")

    private var started = false

    fun start() {
        check(!started) { "$name already started" }
        started = true

        scope.launch {
            onStart(scope)
            for (msg in mailbox) {
                try {
                    handle(msg)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(e, msg)
                }
            }
        }
    }

    fun stop() {
        started = false
        mailbox.close()
        scope.cancel()
    }

    suspend fun tell(msg: T) {
        mailbox.send(msg)
    }

    protected suspend fun <E : Any> subscribe(kClass: KClass<E>) {
        eventBus.subscribe(scope, kClass) { event ->
            wrapEvent(event)?.let { mailbox.send(it) }
        }
    }

    abstract suspend fun handle(msg: T)

    open suspend fun onStart(scope: CoroutineScope) {}

    open suspend fun onError(e: Exception, msg: T) {
        logger.error("$name unhandled error on $msg: ${e.message}", e)
    }

    open suspend fun wrapEvent(event: Any): T? = null
}
