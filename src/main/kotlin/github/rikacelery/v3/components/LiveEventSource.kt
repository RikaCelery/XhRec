package github.rikacelery.v3.components

import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.events.*
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

sealed interface LiveEventMsg
data class OnLiveEvent(val event: Any) : LiveEventMsg
data class OnWsMessage(val text: String) : LiveEventMsg
data class OnWsSubscribe(val roomId: Long) : LiveEventMsg
data class OnWsUnsubscribe(val roomId: Long) : LiveEventMsg

class LiveEventSource(
    private val authToken: String,
    eventBus: EventBus,
    parentScope: CoroutineScope
) : Actor<LiveEventMsg>("LiveEventSource", eventBus, parentScope) {

    private val subscribed = ConcurrentHashMap.newKeySet<Long>()
    private val roomStatuses = ConcurrentHashMap<Long, String>()
    private var wsSession: WebSocketSession? = null
    private val seq = AtomicInteger(1)

    private val channels = listOf(
        "userBanned", "broadcastChanged", "streamChanged",
        "newChatMessage", "newTip", "userJoined", "userLeft",
        "broadcastStarted", "broadcastStopped", "broadcastSettingsChanged",
        "modelShowed", "modelChanged", "moodChanged", "goalUpdated",
        "lovenseLevelChanged", "lovenseStatus", "modelAwayChanged"
    )

    override suspend fun onStart(scope: CoroutineScope) {
        subscribe<RecordingStarted>(RecordingStarted::class)
        subscribe<RecordingStopped>(RecordingStopped::class)
        subscribe<RoomStatusChanged>(RoomStatusChanged::class)
        scope.launch { connectWebSocket() }
    }

    override suspend fun wrapEvent(event: Any): LiveEventMsg? = when (event) {
        is RecordingStarted -> OnLiveEvent(event)
        is RecordingStopped -> OnLiveEvent(event)
        is RoomStatusChanged -> OnLiveEvent(event)
        else -> null
    }

    override suspend fun handle(msg: LiveEventMsg) {
        when (msg) {
            is OnLiveEvent -> when (val event = msg.event) {
                is RecordingStarted -> subscribeRoom(event.roomId)
                is RecordingStopped -> unsubscribeRoom(event.roomId)
                is RoomStatusChanged -> roomStatuses[event.roomId] = event.newStatus
                else -> {}
            }

            is OnWsSubscribe -> sendWsSubscribe(msg.roomId)
            is OnWsUnsubscribe -> sendWsUnsubscribe(msg.roomId)
            is OnWsMessage -> dispatch(msg.text)
        }
    }

    private suspend fun CoroutineScope.connectWebSocket() {
        var backoff = 1000L
        while (isActive) {
            try {
                val client = HttpClient { install(WebSockets) }
                client.webSocket("wss://websocket-v6.xhamsterlive.com/connection/websocket") {
                    wsSession = this
                    send(authFrame(authToken))
                    subscribed.forEach { send(subscribeFrame(it)) }

                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            dispatch(frame.readText())
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                wsSession = null
                logger.error("WS error: ${e.message}, reconnecting in ${backoff}ms")
                delay(backoff)
                backoff = minOf(backoff * 2, 30_000L)
            }
        }
    }

    private fun authFrame(token: String): String {
        return """{"connect":{"token":"$token","name":"js"},"id":${seq.incrementAndGet()}}"""
    }

    private fun subscribeFrame(roomId: Long): String {
        val chans = channels.joinToString("\",\"") { "room:${roomId}:$it" }
        return """{"subscribe":{"channel":"$chans"},"id":${seq.incrementAndGet()}}"""
    }

    private fun unsubscribeFrame(roomId: Long): String {
        val chans = channels.joinToString("\",\"") { "room:${roomId}:$it" }
        return """{"unsubscribe":{"channel":"$chans"},"id":${seq.incrementAndGet()}}"""
    }

    private suspend fun subscribeRoom(roomId: Long) {
        if (subscribed.add(roomId)) {
            wsSession?.let {
                try {
                    it.send(Frame.Text(subscribeFrame(roomId)))
                } catch (_: Exception) {
                }
            }
        }
    }

    private suspend fun unsubscribeRoom(roomId: Long) {
        if (subscribed.remove(roomId)) {
            wsSession?.let {
                try {
                    it.send(Frame.Text(unsubscribeFrame(roomId)))
                } catch (_: Exception) {
                }
            }
        }
    }

    private suspend fun sendWsSubscribe(roomId: Long) {
        wsSession?.let {
            try {
                it.send(Frame.Text(subscribeFrame(roomId)))
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun sendWsUnsubscribe(roomId: Long) {
        wsSession?.let {
            try {
                it.send(Frame.Text(unsubscribeFrame(roomId)))
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun dispatch(raw: String) {
        for (line in raw.lines())
            try {
                val json = Json.parseToJsonElement(line).jsonObject
                val data = json["data"]?.jsonObject ?: return

                val type = data["type"]?.jsonPrimitive?.content ?: return
                val channel = data["channel"]?.jsonPrimitive?.content ?: return
                val roomId = channel.substringAfter("room:").substringBefore(":").toLongOrNull() ?: return

                when (type) {
                    "broadcastChanged", "streamChanged" -> {
                        val bc = data["broadcast"]?.jsonObject
                        val status = bc?.get("status")?.jsonPrimitive?.content ?: "offline"
                        val oldStatus = roomStatuses[roomId] ?: ""
                        logger.debug("WS event: type={}, roomId={}, status={}", type, roomId, status)
                        roomStatuses[roomId] = status
                        eventBus.publish(RoomStatusChanged(roomId, oldStatus, status))

                        if (type == "streamChanged") {
                            val qualities = bc?.get("qualities")?.jsonArray?.mapNotNull {
                                it.jsonObject["id"]?.jsonPrimitive?.content
                            } ?: emptyList()
                            if (qualities.isNotEmpty()) {
                                logger.debug("WS qualities: roomId={}, qualities={}", roomId, qualities)
                                eventBus.publish(QualitiesAvailable(roomId, qualities))
                            }
                        }
                    }

                    "newChatMessage", "newTip", "lovenseLevelChanged", "lovenseStatus" -> {
                        eventBus.publish(LiveMessage(roomId, type, data))
                    }
                }
            } catch (_: Exception) { /* ignore malformed messages */
            }
    }
}
