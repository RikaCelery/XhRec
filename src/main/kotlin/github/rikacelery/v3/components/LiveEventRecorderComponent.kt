package github.rikacelery.v3.components

import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.DataChannel
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.data.StreamEvent
import github.rikacelery.v3.events.LiveMessage
import github.rikacelery.v3.events.RecordingStarted
import github.rikacelery.v3.events.RecordingStopped
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

sealed interface RecorderMsg
data class OnRecorderEvent(val event: Any) : RecorderMsg

/**
 * Persists the platform WebSocket traffic for rooms that are currently recording.
 *
 * ## Why this exists
 *
 * `LiveEventSource` publishes every accepted `push` frame as a [LiveMessage], but publishing is
 * only half the job — the `.event` sidecar that the cutter reads its chat/tip/toy lanes from is
 * written by [WriterComponent] from [StreamEvent]s on the data channel, and something has to turn
 * one into the other. That bridge was dropped during the FSM refactor (`9457976`): `LiveMessage`
 * ended up with no subscriber and `StreamEvent` with no construction site, so `.event` files
 * silently stopped being produced entirely while recordings kept succeeding.
 *
 * Nothing fails loudly in that state, which is what made it worth a dedicated actor and a
 * dedicated test: the only symptom is that every downstream event lane goes empty.
 *
 * ## What gets written
 *
 * One JSON line per platform event, in the envelope [github.rikacelery.cutter.events.EventParser]
 * already unwraps (`{"type": ..., "data": ...}`):
 *
 * ```json
 * {"type":"newChatMessage","recordedAt":"2026-09-13T09:12:03.481Z","data":{...}}
 * ```
 *
 * The payload is the channel's own `data` object, byte-for-byte as the platform sent it, so the
 * parser's timestamp whitelist keeps working unchanged. `recordedAt` is this process's own receipt
 * time and is deliberately a *sibling* of `data`, not a field inside it: the parser never consults
 * it, and it is not the platform's occurrence time, so it must not be mistaken for one. It stays in
 * the line purely so a `.event` file can be lined up against XhRec's own logs when the platform
 * timestamp is missing or turns out to be stale.
 *
 * [LiveMessage.type] is the channel name with its `@roomId` suffix already stripped by
 * `LiveEventSource.dispatch`, which is exactly the key `EventParser.classify` switches on.
 */
class LiveEventRecorderComponent(
    private val dataChannel: DataChannel,
    eventBus: EventBus,
    parentScope: CoroutineScope
) : Actor<RecorderMsg>("LiveEventRecorderComponent", eventBus, parentScope) {

    /** Rooms with an open recording. Kept here so a room's events cannot leak into another file. */
    private val recordingRooms = ConcurrentHashMap.newKeySet<Long>()

    override suspend fun onStart(scope: CoroutineScope) {
        subscribe(LiveMessage::class)
        subscribe(RecordingStarted::class)
        subscribe(RecordingStopped::class)
    }

    override suspend fun wrapEvent(event: Any): RecorderMsg? = when (event) {
        // A room that is not recording needs no sidecar; the check lives in the handler so the
        // mailbox stays cheap and `handle` stays the single place that decides what is persisted.
        is LiveMessage, is RecordingStarted, is RecordingStopped -> OnRecorderEvent(event)
        else -> null
    }

    override suspend fun handle(msg: RecorderMsg) {
        when (msg) {
            is OnRecorderEvent -> when (val event = msg.event) {
                is RecordingStarted -> recordingRooms.add(event.roomId)
                is RecordingStopped -> recordingRooms.remove(event.roomId)
                is LiveMessage -> persist(event)
                else -> Unit
            }
        }
    }

    private suspend fun persist(event: LiveMessage) {
        if (event.roomId !in recordingRooms) return
        if (event.type.isEmpty()) return
        val line = buildJsonObject {
            put("type", event.type)
            // Receipt time, not occurrence time — see the class note on why it sits beside `data`.
            put("recordedAt", Instant.now().toString())
            put("data", event.body)
        }.toString()
        dataChannel.send(StreamEvent(event.roomId, Instant.now(), line))
    }
}
