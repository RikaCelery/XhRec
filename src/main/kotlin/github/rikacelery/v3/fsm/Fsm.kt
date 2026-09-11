package github.rikacelery.v3.fsm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration

// ==========================================
// 1. Exceptions
// ==========================================
sealed class FsmException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    /** Illegal transition (undefined event / explicit ERROR / re-entrant drive) */
    class IllegalTransition(message: String) : FsmException(message)

    /** Action threw while executing */
    class ActionFail(message: String, cause: Throwable) : FsmException(message, cause)
}

// ==========================================
// 2. Transition target and history record
// ==========================================
sealed interface Target<out S>
object KEEP : Target<Nothing>
object ERROR : Target<Nothing>
data class NextState<S>(val state: S) : Target<S>

data class TransitionRecord<S, E>(
    val timestamp: String,
    val fromState: S,
    val event: E,
    val data: Any?,
    val target: Target<S>
) {
    override fun toString(): String {
        val targetStr = when (target) {
            is ERROR -> "ERROR"
            is KEEP -> "KEEP"
            is NextState -> "-> ${target.state}"
        }
        val dataStr = if (data != null) " [data=$data]" else ""
        return "[$timestamp] $fromState --($event)--> $targetStr$dataStr"
    }
}

// ==========================================
// 3. State machine core engine
// ==========================================
class StateMachine<S, E, D, C>(
    initialState: S,
    private val matrix: Map<S, Map<E, Transition<S, E, D, C>>>,
    private val context: C,
    private val maxHistorySize: Int = 10
) {
    @Volatile
    var currentState: S = initialState
        private set

    private var driving = false
    private val historyLock = Any()
    private val _history = ArrayDeque<TransitionRecord<S, E>>(maxHistorySize)

    /**
     * Snapshot of the recent transitions. Guarded because diagnostics read it from the HTTP
     * thread while the owning actor keeps driving transitions on its own loop.
     */
    val history: List<TransitionRecord<S, E>>
        get() = synchronized(historyLock) { _history.toList() }

    private val traceLogger = LoggerFactory.getLogger("v3.Fsm")

    /** Transition-legality gate: the event exists and its target is not ERROR */
    fun canDrive(event: E): Boolean {
        val t = matrix[currentState]?.get(event) ?: return false
        return t.target !is ERROR
    }

    fun drive(event: E, data: D? = null) {
        if (driving) {
            dumpHistoryAndThrow(
                FsmException.IllegalTransition("Re-entrant drive: action for state [$currentState] event [$event] must not call drive again")
            )
        }
        driving = true
        try {
            val transition = matrix[currentState]?.get(event)
                ?: dumpHistoryAndThrow(
                    FsmException.IllegalTransition("No transition defined for state [$currentState] event [$event]")
                )

            recordTransition(currentState, event, data, transition.target)

            val effectiveNewState = when (val target = transition.target) {
                is NextState -> target.state
                else -> currentState
            }
            traceLogger.trace(
                "EVT: {}  {} -> {} / action={}",
                event, currentState, effectiveNewState, transition.hasAction
            )

            when (val target = transition.target) {
                is ERROR -> dumpHistoryAndThrow(
                    FsmException.IllegalTransition("State [$currentState] explicitly rejected event [$event]")
                )
                is KEEP -> transition.invoke(context, data)
                is NextState -> {
                    transition.invoke(context, data)
                    currentState = target.state
                }
            }
        } finally {
            driving = false
        }
    }

    fun driveCatch(event: E, data: D? = null): FsmException? = try {
        drive(event, data)
        null
    } catch (e: FsmException) {
        e
    }

    private fun recordTransition(from: S, event: E, data: Any?, target: Target<S>) {
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
        synchronized(historyLock) {
            if (_history.size >= maxHistorySize) {
                _history.removeFirst()
            }
            _history.addLast(TransitionRecord(time, from, event, data, target))
        }
    }

    private fun dumpHistoryAndThrow(exception: FsmException): Nothing {
        val snapshot = synchronized(historyLock) { _history.toList() }
        val sb = StringBuilder()
        sb.appendLine("=".repeat(60))
        val title = if (exception is FsmException.ActionFail) "ACTION CRASH" else "FSM ILLEGAL TRANSITION"
        sb.appendLine("$title: ${exception.message}")
        sb.appendLine("recent ${snapshot.size} transitions:")
        if (snapshot.isEmpty()) {
            sb.appendLine("   (no history, failed at initial state)")
        } else {
            snapshot.forEachIndexed { index, record ->
                sb.appendLine("   ${index + 1}. $record")
            }
        }
        sb.appendLine("=".repeat(60))
        LoggerFactory.getLogger("v3.Fsm").error(sb.toString())
        throw exception
    }
}

// ==========================================
// 4. DSL builders
// ==========================================
class Transition<S, E, D, C> {
    var target: Target<S> = ERROR
        private set

    private var action: ((C, D?) -> Unit)? = null

    internal val hasAction: Boolean get() = action != null

    infix fun to(state: S): Transition<S, E, D, C> = apply { target = NextState(state) }
    infix fun to(target: Target<S>): Transition<S, E, D, C> = apply { this.target = target }

    /** action: `this` = context (entry), parameter = common D (null when no data) */
    infix fun action(block: C.(D?) -> Unit): Transition<S, E, D, C> {
        this.action = { ctx, d -> ctx.block(d) }
        return this
    }

    internal fun invoke(ctx: C, data: D?) {
        try {
            action?.invoke(ctx, data)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw FsmException.ActionFail(e.message ?: e.javaClass.simpleName, e)
        }
    }
}

class StateBuilder<S, E, D, C> {
    internal val transitions = mutableMapOf<E, Transition<S, E, D, C>>()
    fun on(event: E): Transition<S, E, D, C> {
        val transition = Transition<S, E, D, C>()
        transitions[event] = transition
        return transition
    }
}

class FsmBuilder<S, E, D, C> {
    private var initialState: S? = null
    private var historySize: Int = 10
    private val matrix = mutableMapOf<S, MutableMap<E, Transition<S, E, D, C>>>()

    fun initial(state: S) { this.initialState = state }
    fun historySize(size: Int) { this.historySize = size }

    fun state(state: S, init: StateBuilder<S, E, D, C>.() -> Unit) {
        val builder = StateBuilder<S, E, D, C>()
        builder.init()
        matrix[state] = builder.transitions
    }

    internal fun build(context: C): StateMachine<S, E, D, C> {
        requireNotNull(initialState) { "initial state must be specified" }
        return StateMachine(initialState!!, matrix, context, historySize)
    }
}

fun <S, E, D, C> buildFsm(context: C, init: FsmBuilder<S, E, D, C>.() -> Unit): StateMachine<S, E, D, C> {
    val builder = FsmBuilder<S, E, D, C>()
    builder.init()
    return builder.build(context)
}

// ==========================================
// 5. Timer: single-shot delayed task owned by an entry
// ==========================================
class Timer<T>(
    private val scope: CoroutineScope,
) {
    @Volatile
    private var job: Job? = null

    /** Single-shot: cancels any previous task, waits [delay], then invokes [onTimeout]. */
    fun start(delay: Duration = Duration.ZERO, onTimeout: suspend () -> T) {
        cancel()
        job = scope.launch {
            kotlinx.coroutines.delay(delay)
            onTimeout()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    val isRunning: Boolean get() = job?.isActive == true
}

// ==========================================
// 6. LoopTimer: fixed-delay loop timer
// ==========================================
class LoopTimer<T>(
    private val scope: CoroutineScope,
) {
    @Volatile
    private var job: Job? = null

    /**
     * Starts a fixed-delay loop: invokes [onTimeout], then sleeps [interval] before the next
     * iteration. The next run starts only after the previous invocation completed, so the
     * observed interval between runs is interval + callback execution time.
     */
    fun start(interval: Duration, onTimeout: suspend () -> T) {
        cancel()
        job = scope.launch {
            while (isActive) {
                onTimeout()
                delay(interval)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    val isRunning: Boolean get() = job?.isActive == true
}
