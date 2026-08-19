package github.rikacelery.v3.components

import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.data.Hosts
import github.rikacelery.v3.data.HostsConfig
import github.rikacelery.v3.events.LiveMessage
import github.rikacelery.v3.events.RecordingStarted
import github.rikacelery.v3.events.RecordingStopped
import github.rikacelery.v3.events.RoomStatusChanged
import github.rikacelery.v3.events.HostsChanged
import github.rikacelery.v3.events.WsDisconnected
import github.rikacelery.v3.events.WsReconnected
import github.rikacelery.v3.utils.ClientManager
import github.rikacelery.v3.utils.HostFailover
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** Supplies the WebSocket auth JWT (fetched from config/initial at startup). */
    private val tokenProvider: suspend () -> String,
    eventBus: EventBus,
    parentScope: CoroutineScope,
    private val wsPoolCount: Int = 3
) : Actor<LiveEventMsg>("LiveEventSource", eventBus, parentScope) {

    private val subscribed = ConcurrentHashMap.newKeySet<Long>()
    private val roomStatuses = ConcurrentHashMap<Long, String>()
    private val seq = AtomicInteger(0)
    private val wsFailover = HostFailover(listOf(HostsConfig.DEFAULT_WS_HOST))
    private val pools = (0 until wsPoolCount).map { WsPool(it) }

    // WS auth JWT: minted per session, validity unknown — assume 5 days and refresh on auth failure.
    @Volatile private var wsToken: String = ""
    @Volatile private var wsTokenFetchedAt: Long = 0L
    private val wsTokenMaxAgeMs = 5L * 24 * 60 * 60 * 1000
    private val wsTokenMutex = Mutex()

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
        subscribe<HostsChanged>(HostsChanged::class)
        applyHostConfig() // pick up the current ws hosts before connecting
        scope.launch {
            // fetch the guest WS token at startup; failures are retried inside each pool loop
            try { ensureWsToken() } catch (e: Exception) {
                logger.warn("Failed to fetch ws token at startup: {}", e.message)
            }
            pools.forEach { pool -> launch { pool.connectLoop() } }
        }
    }

    override suspend fun wrapEvent(event: Any): LiveEventMsg? = when (event) {
        is RecordingStarted -> OnLiveEvent(event)
        is RecordingStopped -> OnLiveEvent(event)
        is RoomStatusChanged -> OnLiveEvent(event)
        is HostsChanged -> OnLiveEvent(event)
        else -> null
    }

    override suspend fun handle(msg: LiveEventMsg) {
        when (msg) {
            is OnLiveEvent -> when (val event = msg.event) {
                is RecordingStarted -> subscribeRoom(event.roomId)
                is RecordingStopped -> unsubscribeRoom(event.roomId)
                is RoomStatusChanged -> roomStatuses[event.roomId] = event.newStatus
                is HostsChanged -> applyHostConfig()
                else -> {}
            }

            is OnWsMessage -> dispatch(msg.text)
        }
    }

    /** Returns a valid WS token, refetching when not yet fetched or older than the 5-day TTL. */
    private suspend fun ensureWsToken(): String = wsTokenMutex.withLock {
        val now = System.currentTimeMillis()
        if (wsToken.isNotEmpty() && now - wsTokenFetchedAt < wsTokenMaxAgeMs) return@withLock wsToken
        wsToken = tokenProvider()
        wsTokenFetchedAt = now
        logger.info("Fetched fresh WebSocket auth token")
        wsToken
    }

    /** Force a token refetch on the next connection attempt (auth failure / stale token). */
    private fun invalidateWsToken() {
        wsToken = ""
    }

    /** Refresh the ws host list from the active config and force every pool to reconnect. */
    private suspend fun applyHostConfig() {
        wsFailover.updateHosts(Hosts.current.webSocketHosts)
        logger.info("WebSocket hosts updated: {}", wsFailover.hosts)
        pools.forEach { pool -> pool.closeSession() }
    }

    private fun poolIndex(roomId: Long): Int =
        Math.floorMod(roomId, wsPoolCount.toLong()).toInt()

    /** One WebSocket connection handling roughly 1/wsPoolCount of the subscribed rooms. */
    private inner class WsPool(private val index: Int) {
        @Volatile var wsSession: WebSocketSession? = null
        private var backoff = 1.seconds

        suspend fun connectLoop() {
            while (scope.isActive) {
                val host = wsFailover.currentHost() ?: HostsConfig.DEFAULT_WS_HOST
                var opened = false
                try {
                    val token = ensureWsToken()
                    val client = ClientManager.getProxiedClient("event_$index", http1 = true)
                    client.webSocket("wss://" + host + "/connection/websocket") {
                        wsSession = this
                        opened = true
                        send(authFrame(token))
                        resubscribeAllForPool(index)
                        eventBus.publish(WsReconnected)
                        var frames = 0
                        for (frame in incoming) {
                            frames++
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                if (text == "{}") {
                                    send("{}")
                                } else {
                                    dispatch(text)
                                }
                            }
                        }
                        // closed by the server without delivering any frame — almost certainly an
                        // auth failure (invalid/expired token) → refetch on the next attempt
                        if (frames == 0) invalidateWsToken()
                    }
                    wsFailover.markSuccess(host)
                    backoff = 1.seconds
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    invalidateWsToken()
                    wsFailover.markFailure(host)
                    logger.error("WS pool {} error on {}: {}, reconnecting in {}ms", index, host, e.message, backoff.inWholeMilliseconds)
                    delay(backoff)
                    backoff = minOf(backoff.inWholeSeconds * 2, 30).seconds
                } finally {
                    if (opened) {
                        wsSession = null
                        eventBus.publish(WsDisconnected)
                    }
                }
            }
        }

        suspend fun closeSession() {
            try {
                wsSession?.close(CloseReason(CloseReason.Codes.NORMAL, "hosts updated"))
            } catch (e: Exception) {
                logger.debug("ws pool {} close on hosts update: {}", index, e.message)
            }
            wsSession = null
        }

        suspend fun subscribeRoom(roomId: Long) {
            if (poolIndex(roomId) == index) wsSession?.sendRoomChannels(roomId)
        }

        suspend fun unsubscribeRoom(roomId: Long) {
            if (poolIndex(roomId) == index) wsSession?.sendRoomUnsubscribes(roomId)
        }

    }

    private suspend fun WebSocketSession.resubscribeAllForPool(poolIdx: Int) {
        globalChannels.forEach { send(subscribeFrame(it)) }
        subscribed.filter { poolIndex(it) == poolIdx }.forEach { sendRoomChannels(it) }
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
            pools[poolIndex(roomId)].subscribeRoom(roomId)
        }
    }

    private suspend fun unsubscribeRoom(roomId: Long) {
        if (subscribed.remove(roomId)) {
            pools[poolIndex(roomId)].unsubscribeRoom(roomId)
        }
    }

    private suspend fun WebSocketSession.sendRoomChannels(roomId: Long) {
        roomChannels.forEach { channel ->
            try {
                send(Frame.Text(subscribeFrame("$channel@$roomId")))
            } catch (e: Exception) {
                logger.error("Failed to send subscribe frame for channel=$channel@$roomId: ${e.message}", e)
            }
        }
    }

    private suspend fun WebSocketSession.sendRoomUnsubscribes(roomId: Long) {
        roomChannels.forEach { channel ->
            try {
                send(Frame.Text(unsubscribeFrame("$channel@$roomId")))
            } catch (e: Exception) {
                logger.error("Failed to send unsubscribe frame for channel=$channel@$roomId: ${e.message}", e)
            }
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
            } catch (e: Exception) { logger.error("Failed to dispatch WS message: ${e.message}", e) }
        }
    }
}
