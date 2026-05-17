package github.rikacelery.v3.core

import github.rikacelery.v3.hooks.EventHook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

class EventBus {
    private val _events = MutableSharedFlow<Any>(
        replay = 0,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<Any> = _events.asSharedFlow()

    private val hooks = mutableListOf<EventHook>()

    fun installHook(hook: EventHook) { hooks.add(hook) }

    suspend fun publish(event: Any) {
        var e: Any? = event
        for (hook in hooks) {
            e = hook.intercept(e ?: return)
        }
        if (e != null) _events.emit(e!!)
    }

    fun <E : Any> subscribe(
        scope: CoroutineScope,
        eventType: KClass<E>,
        handler: suspend (E) -> Unit
    ) {
        scope.launch {
            events.filterIsInstance(eventType).collect { handler(it) }
        }
    }
}
