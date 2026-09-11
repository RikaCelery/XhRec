package github.rikacelery.v3.core

import github.rikacelery.v3.fsm.ERROR
import github.rikacelery.v3.fsm.KEEP
import github.rikacelery.v3.fsm.NextState
import github.rikacelery.v3.fsm.StateMachine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * An internal-state view of one component, served by `GET /diagnose`.
 *
 * Implementations are called from an HTTP coroutine, **not** from the actor loop, so they must
 * read state that is safe to read concurrently (a `ConcurrentHashMap`, or a snapshot guarded by
 * the owner). They are strictly best-effort observation: a diagnose call must never mutate
 * component state, must never suspend on the actor's mailbox, and must stay cheap — this is the
 * endpoint an operator reaches for when something is stuck.
 *
 * [section] selects a sub-view (`summary`, `entries`, `history`, …); [args] carries free-form
 * filters such as `room=<id>` and `limit=<n>`. The returned object is embedded verbatim in the
 * response, so the shape is owned by each component.
 */
interface Diagnosable {

    /** Stable key used in `/diagnose?actor=<name>`; defaults to the actor name. */
    val diagnoseName: String

    /** Sections this component understands, `summary` first. */
    val diagnoseSections: List<String> get() = listOf(SECTION_SUMMARY)

    /** Builds the view for [section]. Unknown sections should fall back to the summary. */
    suspend fun diagnose(section: String, args: Map<String, String>): JsonObject

    companion object {
        const val SECTION_SUMMARY = "summary"
        const val SECTION_ENTRIES = "entries"
        const val SECTION_HISTORY = "history"
    }
}

/**
 * Registry of live components, so the HTTP layer can enumerate them without holding a reference
 * to every actor. `Actor` registers itself on start and removes itself on stop.
 */
object Diagnostics {

    private val components = ConcurrentHashMap<String, Diagnosable>()

    fun register(component: Diagnosable) {
        components[component.diagnoseName] = component
    }

    fun unregister(name: String) {
        components.remove(name)
    }

    fun names(): List<String> = components.keys.sorted()

    fun get(name: String): Diagnosable? = components[name]

    fun all(): List<Diagnosable> = components.values.sortedBy { it.diagnoseName }

    /** Index of every registered component and the sections it exposes. */
    fun index(): JsonObject = buildJsonObject {
        put("components", buildJsonArray {
            all().forEach { component ->
                add(buildJsonObject {
                    put("name", component.diagnoseName)
                    put("sections", buildJsonArray {
                        component.diagnoseSections.forEach { add(JsonPrimitive(it)) }
                    })
                })
            }
        })
    }
}

/**
 * Renders a state machine's current state plus its bounded transition history.
 *
 * History is the whole point: a stuck room is usually explained by *how* it got to its current
 * state (e.g. a resume mark carried across a stream change), not by the state alone.
 */
fun <S, E, D, C> StateMachine<S, E, D, C>.diagnose(): JsonObject = buildJsonObject {
    put("state", currentState.toString())
    put("history", history.toJsonArray())
}

private fun <S, E> List<github.rikacelery.v3.fsm.TransitionRecord<S, E>>.toJsonArray(): JsonArray =
    buildJsonArray {
        this@toJsonArray.forEach { record ->
            add(buildJsonObject {
                put("at", record.timestamp)
                put("from", record.fromState.toString())
                put("event", record.event.toString())
                put("target", when (val t = record.target) {
                    is KEEP -> "KEEP"
                    is ERROR -> "ERROR"
                    is NextState -> "-> ${t.state}"
                })
                if (record.data != null) put("data", BusMonitor.shorten(record.data))
            })
        }
    }
