package github.rikacelery.v3.core

import github.rikacelery.v3.fsm.buildFsm
import github.rikacelery.v3.utils.SensitiveStringRegistry
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The diagnose view renders each state machine transition's data verbatim, and that data carries
 * playlist URLs — the one place where the operator sees *why* a room moved.
 *
 * Two properties are load-bearing: the string has to be long enough to keep the tail (which is
 * where `failReason` and `tokenFailure` sit), and it must not carry the credentials the URL is
 * signed with. A room id is not a credential and stays readable here: the diagnose view is about a
 * room the operator already knows.
 */
class DiagnoseHistoryTest {

    private enum class Event { Go }

    private enum class State { Idle, Busy }

    private data class TransitionData(val playlistUrl: String, val failReason: String)

    private fun transitionJson(data: TransitionData): String {
        val machine = buildFsm<State, Event, TransitionData, Unit>(Unit) {
            initial(State.Idle)
            state(State.Idle) { on(Event.Go) to State.Busy }
            state(State.Busy) {}
        }
        machine.drive(Event.Go, data)
        val history = machine.diagnose()["history"]!!.jsonArray
        assertEquals(1, history.size)
        return history.first().jsonObject["data"]!!.jsonPrimitive.content
    }

    @Test
    fun `transition data keeps its tail instead of being cut at the old limit`() {
        val previous = SensitiveStringRegistry.enabled
        SensitiveStringRegistry.enabled = true
        try {
            val rendered = transitionJson(
                TransitionData(
                    playlistUrl = "https://cdn.test/hls/1001/media/1001_720p.m3u8?psch=v2&pkey=secret&aclAuth=signed",
                    failReason = "x".repeat(320) + "TAIL-MARKER"
                )
            )

            assertTrue(
                rendered.contains("TAIL-MARKER"),
                "the reason at the end of a transition must survive the diagnose view: ${rendered.takeLast(60)}"
            )
            assertTrue(rendered.contains("aclAuth=***"), "the token in the URL must be masked: $rendered")
            assertTrue(rendered.contains("pkey=***"), "the stream key must be masked: $rendered")
            assertTrue(
                rendered.contains("/hls/1001/media/"),
                "the room id is not a credential and stays readable here: $rendered"
            )
        } finally {
            SensitiveStringRegistry.enabled = previous
        }
    }
}
