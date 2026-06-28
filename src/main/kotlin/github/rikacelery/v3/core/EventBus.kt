package github.rikacelery.v3.core

import github.rikacelery.v3.events.CommandAck
import github.rikacelery.v3.events.CommandEnvelope
import github.rikacelery.v3.hooks.EventHook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

class EventBus {
    private val logger = LoggerFactory.getLogger("v3.EventBus")

    private val _events = MutableSharedFlow<Any>(
        replay = 0,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<Any> = _events.asSharedFlow()

    /*
     * RequestBus commands are the control plane: losing either CommandEnvelope
     * or CommandAck leaves HTTP handlers waiting until their timeout and makes
     * the dashboard render blank. Keep them off the best-effort data/event
     * stream above, whose DROP_OLDEST policy is intentional for high-volume
     * telemetry/download events.
     */
    private val _commands = MutableSharedFlow<Any>(
        replay = 0,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.SUSPEND
    )
    private val commands: SharedFlow<Any> = _commands.asSharedFlow()

    private val hooks = mutableListOf<EventHook>()

    // backlog tracking
    private val backlogLock = Any()
    private var backlogStartTime: Long = 0
    private var backlogTotal: Long = 0
    private val backlogByType = mutableMapOf<String, Long>()
    private var lastBacklogReportTime: Long = 0

    fun installHook(hook: EventHook) { hooks.add(hook) }

    suspend fun publish(event: Any) {
        var e: Any? = event
        for (hook in hooks) {
            e = hook.intercept(e ?: return)
        }
        if (e == null) return

        if (e is CommandEnvelope || e is CommandAck) {
            if (!_commands.tryEmit(e)) {
                _commands.emit(e)
            }
            return
        }

        if (_events.tryEmit(e)) {
            checkBacklogCleared()
        } else {
            recordBacklog(e)
            _events.emit(e)
        }
    }

    private fun recordBacklog(event: Any) {
        val now = System.currentTimeMillis()
        synchronized(backlogLock) {
            if (backlogStartTime == 0L) {
                backlogStartTime = now
                lastBacklogReportTime = now
                logger.warn(
                    "EventBus buffer full, starting to back up. first event: {}",
                    event::class.simpleName
                )
            }
            backlogTotal++
            val typeName = event::class.simpleName ?: "unknown"
            backlogByType.merge(typeName, 1L, Long::plus)

            val elapsed = now - lastBacklogReportTime
            if (elapsed >= 30_000 || backlogTotal % 1000L == 0L) {
                val topTypes = backlogByType.entries
                    .sortedByDescending { it.value }
                    .take(5)
                    .joinToString(", ") { "${it.key}=${it.value}" }
                logger.warn(
                    "EventBus backlog: {} events over {}s. top types: [{}]",
                    backlogTotal, (now - backlogStartTime) / 1000, topTypes
                )
                lastBacklogReportTime = now
            }
        }
    }

    private fun checkBacklogCleared() {
        synchronized(backlogLock) {
            if (backlogStartTime != 0L) {
                val duration = System.currentTimeMillis() - backlogStartTime
                logger.info(
                    "EventBus backlog cleared. total events: {}, duration: {}ms, top backlog type: {}",
                    backlogTotal, duration,
                    backlogByType.maxByOrNull { it.value }?.key ?: "none"
                )
                backlogStartTime = 0
                backlogTotal = 0
                backlogByType.clear()
                lastBacklogReportTime = 0
            }
        }
    }

    fun <E : Any> subscribe(
        scope: CoroutineScope,
        eventType: KClass<E>,
        handler: suspend (E) -> Unit
    ) {
        scope.launch {
            val source = if (eventType == CommandEnvelope::class || eventType == CommandAck::class) {
                commands
            } else {
                events
            }
            source.filterIsInstance(eventType).collect { handler(it) }
        }
    }
}
