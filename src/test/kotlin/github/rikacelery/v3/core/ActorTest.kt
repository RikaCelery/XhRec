package github.rikacelery.v3.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertTrue

sealed interface TestMsg
data class Msg(val v: String) : TestMsg
data class Bomb(val v: String) : TestMsg
data class RoutedMsg(val v: String) : TestMsg

class TestActor(
    name: String,
    eventBus: EventBus,
    parentScope: CoroutineScope,
    mailboxCapacity: Int = 256,
    private val handler: suspend (TestMsg) -> Unit = {}
) : Actor<TestMsg>(name, eventBus, parentScope, mailboxCapacity) {

    val handled = mutableListOf<String>()
    val errors = mutableListOf<Pair<Exception, TestMsg>>()

    override suspend fun handle(msg: TestMsg) {
        val v = (msg as? Msg)?.v ?: (msg as? Bomb)?.v ?: (msg as? RoutedMsg)?.v ?: "unknown"
        handled.add(v)
        handler(msg)
        if (msg is Bomb) throw RuntimeException("boom: $v")
    }

    override suspend fun onError(e: Exception, msg: TestMsg) {
        errors.add(e to msg)
    }

    suspend fun subscribeRouted(kClass: KClass<out Any>) {
        subscribe(kClass)
    }

    override suspend fun wrapEvent(event: Any): TestMsg? = when (event) {
        is String -> RoutedMsg(event)
        else -> null
    }
}

class ActorTest {

    @Test
    fun `tell order equals handle order`() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus()
        val actor = TestActor("test", bus, this)
        actor.start()

        actor.tell(Msg("a"))
        actor.tell(Msg("b"))
        actor.tell(Msg("c"))
        actor.stop()

        assertEquals(listOf("a", "b", "c"), actor.handled)
    }

    @Test
    fun `handle throws non-CancellationException triggers onError and continues`() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus()
        val actor = TestActor("test", bus, this)
        actor.start()

        actor.tell(Msg("before"))
        actor.tell(Bomb("kaboom"))
        actor.tell(Msg("after"))
        actor.stop()

        assertEquals(listOf("before", "kaboom", "after"), actor.handled)
        assertEquals(1, actor.errors.size)
        assertTrue(actor.errors[0].first.message?.contains("boom") == true)
        assertTrue(actor.errors[0].second is Bomb)
    }

    @Test
    fun `start then stop closes mailbox and cancels coroutine`() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus()
        val actor = TestActor("test", bus, this)
        actor.start()

        actor.tell(Msg("x"))
        actor.stop()

        var threw = false
        try {
            actor.tell(Msg("y"))
        } catch (_: Exception) {
            threw = true
        }
        assertTrue(threw, "tell after stop should throw")
    }

    @Test
    fun `wrapEvent routes events into mailbox`() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus()
        val actor = TestActor("test", bus, this)
        actor.start()
        actor.subscribeRouted(String::class)

        bus.publish("from-event-bus")
        actor.stop()

        assertEquals(listOf("from-event-bus"), actor.handled)
    }

    @Test
    fun `multiple actors run independently`() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus()
        val a = TestActor("a", bus, this)
        val b = TestActor("b", bus, this)
        a.start()
        b.start()

        a.tell(Msg("a1"))
        b.tell(Msg("b1"))
        a.tell(Msg("a2"))
        b.tell(Msg("b2"))
        a.stop()
        b.stop()

        assertEquals(listOf("a1", "a2"), a.handled)
        assertEquals(listOf("b1", "b2"), b.handled)
    }
}
