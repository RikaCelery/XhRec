package github.rikacelery.v3.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

abstract class Actor<T : Any>(
    val name: String,
    protected val eventBus: EventBus,
    parentScope: CoroutineScope,
    private val mailboxCapacity: Int = 256,
    private val slowHandlerThresholdMs: Long = 500
) : Diagnosable {
    private val mailbox = Channel<T>(capacity = mailboxCapacity)

    /**
     * Messages accepted but not yet handled. Kept by hand rather than read from the channel so it
     * needs no experimental API, and it is the cheapest way to tell "this actor is behind" from
     * "this actor is idle" when a component looks stuck.
     */
    private val mailboxDepth = AtomicInteger(0)
    protected val logger = LoggerFactory.getLogger(name)
    internal val scope =
        parentScope + SupervisorJob() + CoroutineName(name) + CoroutineExceptionHandler { context, throwable ->
            logger.error("Unhandled exception {}",context[CoroutineName.Key], throwable)
        }
    /** Component-level async scope: FSM entries derive their Timer / launch helpers from here */
    internal val ioScope =
        parentScope + SupervisorJob() + CoroutineName("$name-io") + CoroutineExceptionHandler { context, throwable ->
            logger.error("Unhandled io exception in {}", context[CoroutineName.Key], throwable)
        }

    private var started = false

    // —— Subscription readiness ——
    // `start()` only launches the loop: the bus subscriptions registered in `onStart` attach to the
    // shared flow a moment later, and the bus drops events for subscribers that have not attached
    // yet. Callers that publish right after starting a component (the test fixture does, the UI
    // routes do once the server is up) can await this instead of racing with it.
    private val subscriptionsRegistered = AtomicInteger(0)
    private val subscriptionsAttached = AtomicInteger(0)
    private val startCompleted = AtomicBoolean(false)
    private val subscriptionsReady = CompletableDeferred<Unit>()

    private fun signalSubscriptionsReady() {
        if (startCompleted.get() && subscriptionsAttached.get() >= subscriptionsRegistered.get()) {
            subscriptionsReady.complete(Unit)
        }
    }

    /**
     * Suspends until [onStart] has returned and every subscription it registered has attached to
     * the bus, so an event published after this returns cannot be missed. Returns false when the
     * subscriptions are still not attached after [timeout].
     */
    suspend fun awaitSubscribed(timeout: Duration = 10.seconds): Boolean {
        signalSubscriptionsReady()
        return withTimeoutOrNull(timeout) { subscriptionsReady.await() } != null
    }

    fun start() {
        check(!started) { "$name already started" }
        started = true
        Diagnostics.register(this)

        scope.launch {
            onStart(scope)
            startCompleted.set(true)
            signalSubscriptionsReady()
            for (msg in mailbox) {
                try {
                    val start = System.nanoTime()
                    handle(msg)
                    val duration = (System.nanoTime() - start) / 1_000_000
                    if (duration > slowHandlerThresholdMs) {
                        logger.warn(
                            "slow handler: actor={}, msg={}, took={}ms",
                            name, msg::class.simpleName, duration
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(e, msg)
                } finally {
                    mailboxDepth.decrementAndGet()
                }
            }
        }
    }

    open fun stop() {
        started = false
        Diagnostics.unregister(name)
        mailbox.close()
        scope.cancel()
    }

    suspend fun tell(msg: T) = enqueue(msg)

    /**
     * Single funnel for every message entering the mailbox, so [mailboxDepth] stays truthful.
     * Bus subscriptions enqueue directly rather than through [tell], and counting only one of the
     * two paths made the depth drift negative.
     */
    private suspend fun enqueue(msg: T) {
        mailboxDepth.incrementAndGet()
        try {
            mailbox.send(msg)
        } catch (e: Throwable) {
            mailboxDepth.decrementAndGet()
            throw e
        }
    }

    protected suspend fun <E : Any> subscribe(kClass: KClass<E>) {
        subscriptionsRegistered.incrementAndGet()
        eventBus.subscribe(
            scope = scope,
            eventType = kClass,
            onAttached = {
                subscriptionsAttached.incrementAndGet()
                signalSubscriptionsReady()
            }
        ) { event ->
            wrapEvent(event)?.let { enqueue(it) }
        }
    }

    abstract suspend fun handle(msg: T)

    open suspend fun onStart(scope: CoroutineScope) {}

    open suspend fun onError(e: Exception, msg: T) {
        logger.error("$name unhandled error on $msg: ${e.message}", e)
    }

    open suspend fun wrapEvent(event: Any): T? = null

    // —— Diagnostics ——
    // Every actor answers `GET /diagnose?actor=<name>` out of the box. Components with real
    // internal state (schedulers, sessions, the writer) override `diagnoseSections`/`diagnose`
    // and merge [baseDiagnose] into their own object so liveness fields are always present.

    override val diagnoseName: String get() = name

    override val diagnoseSections: List<String>
        get() = listOf(Diagnosable.SECTION_SUMMARY)

    override suspend fun diagnose(section: String, args: Map<String, String>): JsonObject = baseDiagnose()

    /** Fields every actor can report: identity, liveness and mailbox backlog. */
    protected fun baseDiagnose(extra: JsonObject = JsonObject(emptyMap())): JsonObject = buildJsonObject {
        put("actor", name)
        put("type", this@Actor::class.simpleName ?: "")
        put("started", started)
        put("mailboxDepth", mailboxDepth.get())
        put("mailboxCapacity", mailboxCapacity)
        extra.forEach { (key, value) -> put(key, value) }
    }
}
