package github.rikacelery.v3.components

import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.events.LiveMessage
import github.rikacelery.v3.events.RecordingStarted
import github.rikacelery.v3.events.RecordingStopped
import github.rikacelery.v3.events.RoomStatusChanged
import github.rikacelery.v3.utils.ClientManager
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

sealed interface LiveEventMsg
data class OnLiveEvent(val event: Any) : LiveEventMsg
data class OnWsMessage(val text: String) : LiveEventMsg


class LiveEventSource(
    private val authToken: String,
    eventBus: EventBus,
    parentScope: CoroutineScope
) : Actor<LiveEventMsg>("LiveEventSource", eventBus, parentScope) {

    private val subscribed = ConcurrentHashMap.newKeySet<Long>()
    private val roomStatuses = ConcurrentHashMap<Long, String>()
    private var wsSession: WebSocketSession? = null
    private val seq = AtomicInteger(0)

    private val globalChannels = listOf(
        "changeConfigFeature",
//        "newModelEvent",
        "lotteryChanged"
    )

    private val roomChannels = listOf(
        "userBanned", "broadcastChanged", "streamChanged",
        "newChatMessage", "newTip", "userJoined", "userLeft",
        "broadcastStarted", "broadcastStopped", "broadcastSettingsChanged",
        "modelShowed", "modelChanged", "moodChanged", "goalUpdated",
        "lovenseLevelChanged", "lovenseStatus", "modelAwayChanged",
        "groupShow",
        "modelDiscountActivated", "modelStatusChanged", "topicChanged",
        "tipMenuUpdated", "goalChanged", "userUpdated",
        "interactiveToyStatusChanged", "deleteChatMessages",
        "tipMenuLanguageDetected", "fanClubUpdated", "modelAppUpdated",
        "newKing",
        "privateStartedV3", "privateEndedV3"
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

            is OnWsMessage -> dispatch(msg.text)
        }
    }

    private suspend fun CoroutineScope.connectWebSocket() {
        var backoff = 1.seconds
        while (isActive) {
            try {
                val client = ClientManager.getProxiedClient("event")
                client.webSocket("wss://websocket-v6.poplive.xyz/connection/websocket") {
                    wsSession = this
                    send(authFrame(authToken))
                    resubscribeAll()
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            if (text == "{}") {
                                send("{}")
                            } else {
                                dispatch(text)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                wsSession = null
                logger.error("WS error: ${e.message}, reconnecting in ${backoff.inWholeMilliseconds}ms")
                delay(backoff)
                backoff = minOf(backoff.inWholeSeconds * 2, 30).seconds
            }
        }
    }

    private suspend fun WebSocketSession.resubscribeAll() {
        globalChannels.forEach { send(subscribeFrame(it)) }
        subscribed.forEach { sendRoomChannels(it) }
    }

    private fun authFrame(token: String): String {
        return """{"connect":{"token":"$token","name":"js"},"id":${seq.incrementAndGet()}}"""
    }

    private fun subscribeFrame(channel: String): String {
        return """{"subscribe":{"channel":"$channel"},"id":${seq.incrementAndGet()}}"""
    }

    private fun unsubscribeFrame(channel: String): String {
        return """{"unsubscribe":{"channel":"$channel"},"id":${seq.incrementAndGet()}}"""
    }

    private suspend fun subscribeRoom(roomId: Long) {
        if (subscribed.add(roomId)) {
            wsSession?.sendRoomChannels(roomId)
        }
    }

    private suspend fun unsubscribeRoom(roomId: Long) {
        if (subscribed.remove(roomId)) {
            wsSession?.sendRoomUnsubscribes(roomId)
        }
    }

    private suspend fun WebSocketSession.sendRoomChannels(roomId: Long) {
        roomChannels.forEach { channel ->
            try {
                send(Frame.Text(subscribeFrame("$channel@$roomId")))
            } catch (_: Exception) {}
        }
    }

    private suspend fun WebSocketSession.sendRoomUnsubscribes(roomId: Long) {
        roomChannels.forEach { channel ->
            try {
                send(Frame.Text(unsubscribeFrame("$channel@$roomId")))
            } catch (_: Exception) {}
        }
    }

    private suspend fun dispatch(raw: String) {
        for (line in raw.lines()) {
            if (line.isBlank()) continue
            try {
                val json = Json.parseToJsonElement(line).jsonObject
                val push = json["push"]?.jsonObject ?: continue
                val channel = push["channel"]?.jsonPrimitive?.content ?: continue
                val type = channel.substringBefore("@")
                val roomId = channel.substringAfter("@").toLongOrNull() ?: continue
                val pub = push["pub"]?.jsonObject ?: continue
                val data = pub["data"]?.jsonObject ?: continue

                if (type == "broadcastChanged" || type == "streamChanged") {
                    val status = data["status"]?.jsonPrimitive?.content
                        ?: data["broadcast"]?.jsonObject?.get("status")?.jsonPrimitive?.content
                        ?: "offline"
                    val oldStatus = roomStatuses[roomId] ?: ""
                    logger.debug("WS event: type={}, roomId={}, status={}", type, roomId, status)
                    roomStatuses[roomId] = status
                    eventBus.publish(RoomStatusChanged(roomId, oldStatus, status))
                }

                eventBus.publish(LiveMessage(roomId, type, data))
            } catch (_: Exception) { /* ignore malformed messages */ }
        }
    }
}