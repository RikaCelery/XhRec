package github.rikacelery.v3.core

import github.rikacelery.v3.hooks.EventHook
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventBusTest {

    @Test
    fun `publish to single subscriber delivers event`() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus()
        val events = mutableListOf<String>()
        bus.subscribe(backgroundScope, String::class) { events.add(it) }

        bus.publish("hello")
        assertEquals(listOf("hello"), events)
    }

    @Test
    fun `publish to multiple subscribers each gets independent copy`() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus()
        val a = mutableListOf<String>()
        val b = mutableListOf<String>()
        bus.subscribe(backgroundScope, String::class) { a.add(it) }
        bus.subscribe(backgroundScope, String::class) { b.add(it) }

        bus.publish("x")
        assertEquals(listOf("x"), a)
        assertEquals(listOf("x"), b)
    }

    @Test
    fun `subscriber only receives matching event type`() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus()
        val strings = mutableListOf<String>()
        val ints = mutableListOf<Int>()
        bus.subscribe(backgroundScope, String::class) { strings.add(it) }
        bus.subscribe(backgroundScope, Int::class) { ints.add(it) }

        bus.publish("s")
        bus.publish(42)
        assertEquals(listOf("s"), strings)
        assertEquals(listOf(42), ints)
    }

    @Test
    fun `hook returns null swallows event`() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus()
        val events = mutableListOf<String>()
        bus.installHook(object : EventHook {
            override suspend fun intercept(event: Any): Any? {
                return if (event == "drop-me") null else event
            }
        })
        bus.subscribe(backgroundScope, String::class) { events.add(it) }

        bus.publish("drop-me")
        bus.publish("keep-me")
        assertEquals(listOf("keep-me"), events)
    }

    @Test
    fun `concurrent publish from multiple coroutines delivers all events`() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus()
        val events = mutableListOf<Int>()
        bus.subscribe(backgroundScope, Int::class) { events.add(it) }

        val jobs = (0 until 10).map { batch ->
            launch {
                repeat(100) { i -> bus.publish(batch * 100 + i) }
            }
        }
        jobs.forEach { it.join() }
        assertEquals(1000, events.size)
        assertEquals((0 until 1000).toSet(), events.toSet())
    }

    @Test
    fun `hook chain all pass`() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus()
        val events = mutableListOf<String>()
        bus.installHook(object : EventHook {
            override suspend fun intercept(event: Any): Any? = "$event-1"
        })
        bus.installHook(object : EventHook {
            override suspend fun intercept(event: Any): Any? = "$event-2"
        })
        bus.subscribe(backgroundScope, String::class) { events.add(it) }

        bus.publish("x")
        assertEquals(listOf("x-1-2"), events)
    }

    @Test
    fun `unsubscribed event type not delivered to other subscribers`() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus()
        val strings = mutableListOf<String>()
        bus.subscribe(backgroundScope, String::class) { strings.add(it) }

        bus.publish(123)
        assertTrue(strings.isEmpty())
    }
}
