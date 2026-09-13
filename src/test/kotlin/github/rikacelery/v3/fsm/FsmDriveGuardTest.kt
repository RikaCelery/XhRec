package github.rikacelery.v3.fsm

import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `drive` refuses a second overlapping drive, and the flag behind that cannot tell the two possible
 * causes apart on its own — which is why it now names the one it found.
 *
 * The distinction matters because the fixes are different. An action that drives again should
 * schedule its event instead; that is a bug in the action and is always reproducible. A drive from
 * *another thread* means a component is driving its FSM from outside the loop that owns it, and that
 * one is a race: the loser of the overlap is dropped, so the room's state silently stops matching
 * what the operator asked for. `RoomDeletionAndActivationIntegrationTest` hit exactly that — an
 * activation driven off the actor loop collided with the loop's own drive, the state machine
 * rejected it as "re-entrant", and the room sat armed with no session and nothing left to move it.
 */
class FsmDriveGuardTest {

    private enum class Event { Go, Next, Back }

    private enum class State { Idle, Busy, Done }

    private class Holder {
        lateinit var machine: StateMachine<State, Event, Unit, Holder>

        fun driveAgain() {
            machine.drive(Event.Next)
        }
    }

    private fun blockingFsm(entered: CountDownLatch, release: CountDownLatch) =
        buildFsm<State, Event, Unit, Unit>(Unit) {
            initial(State.Idle)
            state(State.Idle) {
                on(Event.Go) to State.Busy action {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
            state(State.Busy) { on(Event.Next) to State.Done }
            state(State.Done) { on(Event.Back) to State.Idle }
        }

    @Test
    fun `a drive from another thread while the owner is driving says so`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val machine = blockingFsm(entered, release)
        val owner = thread(name = "fsm-owner") { machine.drive(Event.Go) }
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the owner thread must be inside drive()")

        val failure = assertFailsWith<FsmException> { machine.drive(Event.Next) }

        assertTrue(
            failure.message.orEmpty().contains("Concurrent drive"),
            "a drive from a second thread is not a re-entrant action: ${failure.message}"
        )
        // The owner is still inside the action, so its transition has not landed yet — and the
        // rejected drive must not have moved anything either.
        assertEquals(State.Idle, machine.currentState)

        release.countDown()
        owner.join(5_000)
        assertEquals(State.Busy, machine.currentState, "the owner's transition still lands")
    }

    @Test
    fun `an action that drives again is still reported as re-entrant`() {
        val holder = Holder()
        holder.machine = buildFsm<State, Event, Unit, Holder>(holder) {
            initial(State.Idle)
            state(State.Idle) { on(Event.Go) to State.Busy action { driveAgain() } }
            state(State.Busy) { on(Event.Next) to State.Done }
            state(State.Done) { on(Event.Back) to State.Idle }
        }

        // The inner drive throws IllegalTransition, and the action wrapper reports it as ActionFail
        // with that message as the cause: either way the text has to name the mistake.
        val failure = assertFailsWith<FsmException> { holder.machine.drive(Event.Go) }

        assertTrue(
            failure.message.orEmpty().contains("Re-entrant drive"),
            "an action that drives again is the action's bug: ${failure.message}"
        )
        // The action's own throw fails the transition, so the state it was moving away from stands.
        assertEquals(State.Idle, holder.machine.currentState)
    }
}
