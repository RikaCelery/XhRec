package github.rikacelery.v3.components

import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.PipelineMetrics
import github.rikacelery.v3.data.Hosts
import github.rikacelery.v3.data.HostsConfig
import github.rikacelery.v3.data.RuntimeTuning
import github.rikacelery.v3.events.HostsChanged
import github.rikacelery.v3.events.LiveMessage
import github.rikacelery.v3.events.QualityChangeHint
import github.rikacelery.v3.events.RecordingStarted
import github.rikacelery.v3.events.RecordingStopped
import github.rikacelery.v3.events.RoomAdded
import github.rikacelery.v3.events.RoomRemoved
import github.rikacelery.v3.events.RoomStatusChanged
import github.rikacelery.v3.events.StreamStatusChanged
import github.rikacelery.v3.events.WsDisconnected
import github.rikacelery.v3.events.WsReconnected
import github.rikacelery.v3.utils.DefaultHttpClientProvider
import github.rikacelery.v3.utils.HostFailover
import github.rikacelery.v3.utils.HttpClientProvider
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

sealed interface LiveEventMsg
data class OnLiveEvent(val event: Any) : LiveEventMsg


class LiveEventSource(
    /** Supplies the WebSocket auth JWT (fetched from config/initial at startup). */
    private val tokenProvider: suspend () -> String,
    eventBus: EventBus,
    parentScope: CoroutineScope,
    /** Lower bound for the connection count; the real count follows the room set. See [wantedPoolCount]. */
    private val wsPoolCount: Int = 3,
    private val httpClientProvider: HttpClientProvider = DefaultHttpClientProvider,
    private val runtimeTuning: RuntimeTuning = RuntimeTuning(),
    private val wsUrlBuilder: (String) -> String = { host -> "wss://$host/connection/websocket" },
    /**
     * Channels one connection is allowed to hold — its subscription budget.
     *
     * The platform answers at most ~200–260 subscribe commands per connection (measured 2026-09-19:
     * a single connection asking for 300/600/1200/1800 channels was answered 197/183/181/164 times,
     * and a paced run of 600 was answered 255 times and refused 326 with `106 limit exceeded`).
     * Whatever the exact number, it is a *total per connection*, not a rate: pacing the same 600
     * channels over 30 s instead of 0 s did not buy a single extra subscription. So the shard has
     * to keep each connection's channel count under it, and offload the rest to another connection.
     */
    private val maxChannelsPerPool: Int = 160,
    /**
     * Subscribe frames per second one connection may send.
     *
     * Not a platform limit but a workaround for its burst behaviour: sending 600 channels as fast as
     * possible earned 183 answers, while the same 600 spaced 20–50 ms apart earned 255 — the
     * difference is frames the server dropped without processing. Pacing costs seconds and buys
     * tens of channels that would otherwise never arrive.
     */
    private val subscribeBurstPerSecond: Int = 50,
    /**
     * Recording rooms assumed when sizing the connections. A recording room holds [roomChannels]
     * instead of [statusChannels], and this allowance is what keeps that growth from pushing a
     * connection over [maxChannelsPerPool]. Deliberately a constant: sizing on the *live*
     * recording count would reconnect every connection whenever a show started or ended, and the
     * reconnect gap is exactly when events are lost.
     */
    private val recordingReserve: Int = 24,
    /** Ceiling on the connection count, so a runaway room list cannot open sockets forever. */
    private val maxPoolCount: Int = 32,
    /**
     * Where each connection's connect loop and sender run.
     *
     * Not the actor scope's `Dispatchers.Default`: a WebSocket handshake parks its *thread* inside
     * `runBlocking` (Ktor's `CryptoKt.generateNonceBlocking`), and on a six-core host
     * `Dispatchers.Default` has six threads — six slow handshakes consumed the whole dispatcher and
     * froze every actor in the process (observed 2026-09-19: no log line, no event, `GetRooms`
     * timing out, CPU idle). Injectable so a virtual-time test can drive the pools itself.
     */
    private val poolDispatcher: CoroutineDispatcher = Dispatchers.IO
) : Actor<LiveEventMsg>("LiveEventSource", eventBus, parentScope) {

    private val subscribed = ConcurrentHashMap.newKeySet<Long>()
    // rooms known to list.conf (from RoomAdded/RoomRemoved) — get status channels even when idle
    private val trackedRooms = ConcurrentHashMap.newKeySet<Long>()
    // rooms currently recording — get the full channel set
    private val recordingRooms = ConcurrentHashMap.newKeySet<Long>()
    private val roomStatuses = ConcurrentHashMap<Long, String>()
    private val streamStatuses = ConcurrentHashMap<Long, String>()
    private val seq = AtomicInteger(0)
    private val rejectedSubscriptions = AtomicInteger(0)
    private val wsFailover = HostFailover(listOf(HostsConfig.DEFAULT_WS_HOST))

    /**
     * The connections, and the jobs running their connect loops.
     *
     * [pools] grows on demand — [placeRoom] appends a connection when every existing one is at its
     * channel budget — and is rebuilt wholesale by [reconcilePools] when the room set changes size.
     * It is read from the connect loops (`resubscribeAll`) as well as from the actor, so it is
     * volatile and every change publishes a fresh immutable list.
     */
    @Volatile
    private var pools: List<WsPool> = emptyList()
    private val poolJobs = mutableListOf<Job>()
    private var resizeJob: Job? = null
    private var nextPoolLaunchAtMs = 0L

    /** Which connection carries a room, and what that costs it. Written on the actor, read by pools. */
    private data class Placement(val pool: Int, val channels: Int)

    private val placement = ConcurrentHashMap<Long, Placement>()

    /**
     * Serialises minting. Each connection mints its **own** token for **every** dial — see
     * [WsPool.mintToken] for why sharing one, or reusing one across a reconnect, loses events.
     */
    private val wsTokenMutex = Mutex()

    private val globalChannels = listOf(
        "changeConfigFeature",
//        "newModelEvent",
        "lotteryChanged"
    )

    /**
     * The channels that decide whether a recording is still supposed to run: the stream lifecycle
     * and the room/model status. They are subscribed before the chat channels, because a
     * `streamChanged: finished` that arrives before its subscription exists is a recording that
     * never stops — and with paced sends the queue for a recording room is ~30 channels deep.
     */
    private val lifecycleChannels = setOf("broadcastChanged", "modelStatusChanged", "streamChanged")

    // minimal channels needed to track status of idle (armed but not recording) rooms
    private val statusChannels = listOf(
        "broadcastChanged", "streamChanged", "broadcastStarted", "broadcastStopped",
        "modelStatusChanged", "broadcastSettingsChanged"
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
        subscribe<RoomAdded>(RoomAdded::class)
        subscribe<RoomRemoved>(RoomRemoved::class)
        subscribe<HostsChanged>(HostsChanged::class)
        createPools(wsPoolCount) // one shard to begin with; RoomAdded raises it as the room list lands
        pools.forEach { pool -> launchPool(pool) }
        applyHostConfig() // pick up the current ws hosts before connecting
    }

    override suspend fun wrapEvent(event: Any): LiveEventMsg? = when (event) {
        is RecordingStarted -> OnLiveEvent(event)
        is RecordingStopped -> OnLiveEvent(event)
        is RoomStatusChanged -> OnLiveEvent(event)
        is RoomAdded -> OnLiveEvent(event)
        is RoomRemoved -> OnLiveEvent(event)
        is HostsChanged -> OnLiveEvent(event)
        else -> null
    }

    override suspend fun handle(msg: LiveEventMsg) {
        when (msg) {
            is OnLiveEvent -> when (val event = msg.event) {
                is RecordingStarted -> {
                    recordingRooms.add(event.roomId)
                    subscribeRoom(event.roomId, full = true)
                }
                is RecordingStopped -> {
                    recordingRooms.remove(event.roomId)
                    // keep status channels for rooms still in list.conf, drop otherwise
                    if (event.roomId in trackedRooms) subscribeRoom(event.roomId, full = false)
                    else unsubscribeRoom(event.roomId)
                }
                is RoomStatusChanged -> roomStatuses[event.roomId] = event.newStatus
                is RoomAdded -> {
                    trackedRooms.add(event.roomId)
                    subscribeRoom(event.roomId, full = false)
                    schedulePoolReconcile()
                }
                is RoomRemoved -> {
                    trackedRooms.remove(event.roomId)
                    unsubscribeRoom(event.roomId)
                    schedulePoolReconcile()
                }
                is HostsChanged -> applyHostConfig()
                else -> {}
            }
        }
    }

    /** Refresh the ws host list from the active config and force every pool to reconnect. */
    private suspend fun applyHostConfig() {
        wsFailover.updateHosts(Hosts.current.webSocketHosts)
        logger.info("WebSocket hosts updated: {}", wsFailover.hosts)
        pools.forEach { pool -> pool.closeSession() }
    }

    /**
     * Replaces the connection set, cancelling the old connect loops and starting the new ones.
     *
     * Every room is re-placed by [placeRoom] and re-subscribed by whichever connection now owns it,
     * because each fresh connection runs [resubscribeAll] before it starts reading. Nothing has to be
     * unsubscribed from the retired connections: they are cancelled, and their channels die with them.
     *
     * The connects are staggered by [RuntimeTuning.wsPoolConnectStagger]. The platform refuses new
     * WebSocket handshakes from a source IP that opens too many in a burst — measured 2026-09-19:
     * after a run of probe handshakes, every further handshake was terminated mid-TLS
     * (`ConnectionResetError` on one client, `SSLHandshakeException` on the production OkHttp stack)
     * while the already-established connections kept streaming. Sixteen simultaneous connects at
     * startup, and again on every resize and host change, is exactly that burst.
     */
    /**
     * Drops every connection and creates [count] fresh ones. Nothing dials until [launchPool], so a
     * caller can register its rooms in [placement] first: a connection that subscribes before the
     * room is registered would leave that room unsubscribed until its next reconnect.
     */
    private fun createPools(count: Int) {
        val retired = poolJobs.toList()
        poolJobs.clear()
        pools.forEach { pool -> PipelineMetrics.forgetWsPool(pool.index) }
        pools = emptyList()
        placement.clear()
        retired.forEach { it.cancel() }
        repeat(count.coerceAtLeast(1)) { createPool() }
    }

    /** Appends one connection to the shard. It stays offline until [launchPool] starts its loop. */
    private fun createPool(): WsPool {
        val pool = WsPool(pools.size)
        pools = pools + pool
        PipelineMetrics.wsPool(pool.index)
        return pool
    }

    /**
     * Starts a connection's connect loop, spaced from the previous launch by
     * [RuntimeTuning.wsPoolConnectStagger] — the platform refuses new WebSocket handshakes from a
     * source IP that opens too many at once.
     */
    private fun launchPool(pool: WsPool) {
        if (pool.launched) return
        pool.launched = true
        val now = System.currentTimeMillis()
        val wait = (nextPoolLaunchAtMs - now).coerceAtLeast(0L)
        nextPoolLaunchAtMs = now + wait + runtimeTuning.wsPoolConnectStagger.inWholeMilliseconds
        // Dispatchers.IO, not the actor scope's Default: a WebSocket handshake runs
        // `CryptoKt.generateNonceBlocking`, which parks its *thread* inside `runBlocking`. On a
        // 6-core box Dispatchers.Default has six threads, so six simultaneous handshakes against a
        // platform that is slow to answer consumed the whole dispatcher and froze every actor in the
        // process (observed 2026-09-19: no log line, no event, `GetRooms` timing out, CPU idle).
        poolJobs += scope.launch(poolDispatcher) {
            if (wait > 0) delay(wait)
            launch { pool.sendLoop() }
            pool.connectLoop()
        }
    }

    /**
     * Places [roomId] on a connection, growing the shard when every connection is at its budget.
     *
     * This is the offload the platform forces: a connection answers only ~200–260 subscribes and
     * silently ignores the rest, so a room that does not fit has to go somewhere else rather than
     * ride along and never be delivered. A room that already fits where it is stays put — moving it
     * would cost an unsubscribe plus a fresh subscribe, and the connection that lost it would have
     * spent its budget for nothing.
     *
     * Returns the pool index, or -1 when even a new connection cannot be opened.
     */
    private suspend fun placeRoom(roomId: Long): Int {
        val want = channelCost(roomId)
        val existing = placement[roomId]
        if (existing != null) {
            val pool = pools.getOrNull(existing.pool)
            if (pool != null && pool.assignedChannels.get() - existing.channels + want <= maxChannelsPerPool) {
                pool.assignedChannels.addAndGet(want - existing.channels)
                placement[roomId] = Placement(existing.pool, want)
                PipelineMetrics.wsPoolChannels(existing.pool, pool.assignedChannels.get())
                return existing.pool
            }
        }

        var target = pools
            .filter { it.assignedChannels.get() + want <= maxChannelsPerPool }
            .minByOrNull { it.assignedChannels.get() }
        if (target == null && pools.size < maxPoolCount) {
            target = createPool()
            logger.info(
                "WS shard grew to {} connections: roomId={} needs {} channels and every connection is at its budget of {}",
                pools.size, roomId, want, maxChannelsPerPool
            )
        }
        if (target == null) {
            // Ceiling reached: oversubscribe the emptiest connection rather than drop the room, and
            // say so — this is the one case where the platform will start refusing channels again.
            target = pools.minByOrNull { it.assignedChannels.get() }
            if (target == null) return -1
            logger.warn(
                "WS shard is at its ceiling of {} connections, roomId={} oversubscribes pool {}",
                maxPoolCount, roomId, target.index
            )
        }

        if (existing != null && existing.pool != target.index) {
            pools.getOrNull(existing.pool)?.let { old ->
                old.assignedChannels.addAndGet(-existing.channels)
                PipelineMetrics.wsPoolChannels(existing.pool, old.assignedChannels.get())
                old.unsubscribeRoom(roomId)
            }
        }
        target.assignedChannels.addAndGet(want)
        placement[roomId] = Placement(target.index, want)
        PipelineMetrics.wsPoolChannels(target.index, target.assignedChannels.get())
        launchPool(target) // only now may it dial: the room is registered for resubscribeAll
        return target.index
    }

    /** Channels a room's subscription occupies on its connection. */
    private fun channelCost(roomId: Long): Int =
        if (roomId in recordingRooms) roomChannels.size else statusChannels.size

    /**
     * Connections needed so that no one of them asks for more than [maxChannelsPerPool] channels.
     *
     * Sized from [subscribed] — the room set, which changes when rooms are added or removed — plus a
     * fixed [recordingReserve] rather than from [recordingRooms]. Sizing on the live recording count
     * would reconnect every connection on each show start and stop, and a reconnect is precisely
     * when events are lost; the reserve is what keeps that growth from being unbounded. This is only
     * the target size: [placeRoom] balances the rooms by fill and grows the shard when the estimate
     * turns out short, so no connection is asked for more than [maxChannelsPerPool].
     */
    private fun wantedPoolCount(): Int {
        val rooms = subscribed.size
        if (rooms == 0) return wsPoolCount
        val reserve = recordingReserve.coerceAtMost(rooms)
        val channels = (rooms - reserve) * statusChannels.size +
            reserve * roomChannels.size + globalChannels.size
        val needed = (channels + maxChannelsPerPool - 1) / maxChannelsPerPool
        return needed.coerceIn(wsPoolCount, maxPoolCount)
    }

    /**
     * Queues a resize for after the current burst of room changes.
     *
     * Rooms arrive in bursts — list.conf bootstraps 232 `RoomAdded` events back to back — and each
     * one can move the shard count. Debouncing collapses the burst into a single reconnect instead
     * of one per room.
     */
    private fun schedulePoolReconcile() {
        resizeJob?.cancel()
        resizeJob = scope.launch {
            delay(runtimeTuning.wsPoolResizeDebounce)
            reconcilePools()
        }
    }

    private suspend fun reconcilePools() {
        val wanted = wantedPoolCount()
        val current = pools.size
        // one pool of hysteresis on the way down: shrinking at a boundary and growing again on the
        // next room add would reconnect every connection twice for nothing
        if (wanted == current || wanted == current - 1) return
        logger.info(
            "WS pools resized: {} -> {} (rooms={}, recording={}, cap={} channels/connection)",
            current, wanted, subscribed.size, recordingRooms.size, maxChannelsPerPool
        )
        val rooms = subscribed.toList()
        createPools(wanted)
        // place every room before any connection dials, so each one's resubscribeAll sees its full
        // set; placeRoom grows the shard again if the estimate was short
        rooms.forEach { roomId -> placeRoom(roomId) }
        pools.forEach { pool -> launchPool(pool) }
    }

    /** One WebSocket connection, carrying the rooms [placement] hands it. */
    private inner class WsPool(val index: Int) {
        @Volatile var wsSession: WebSocketSession? = null

        /** Set once [launchPool] has started this connection's loop. */
        @Volatile var launched = false

        /**
         * A guest session of this connection's own, minted fresh for every dial.
         *
         * The platform tracks subscriptions per *session*, not per socket. Sharing one token across
         * the shard looked harmless — an A/B on first delivery showed no difference — but when a
         * connection drops and re-subscribes its channels with the session that already holds them,
         * the platform answers `{"code":105,"already subscribed"}` and the pushes stay bound to the
         * dead socket. Production showed exactly that: all 13 pools "connected", 105-refusals
         * accumulating per pool, and delivery down to ~4 LiveMessage/min about 1.7 h after each
         * restart — which is the pools' read timeout. A fresh session has nothing stale to collide
         * with, so a reconnected pool starts receiving again immediately.
         */
        private suspend fun mintToken(): String = wsTokenMutex.withLock {
            val token = tokenProvider()
            logger.info("Minted WebSocket session for pool {}", index)
            token
        }

        /** Channels this connection holds; the budget it must not exceed (see [maxChannelsPerPool]). */
        val assignedChannels = AtomicInteger(0)

        /**
         * Frames waiting for this connection.
         *
         * The actor only ever enqueues: a socket that stopped draining must not be able to suspend
         * the component that publishes every room's events. Unbounded because a full queue would
         * have to be handled by dropping events anyway, and the queue only grows while the socket
         * is stalled.
         */
        private val outbox = Channel<String>(Channel.UNLIMITED)
        private val queued = java.util.concurrent.atomic.AtomicInteger(0)
        private var backoff = runtimeTuning.webSocketReconnectInitial

        /**
         * Sends one subscribe/unsubscribe frame, spaced from the previous one.
         *
         * Pacing is what makes the platform answer instead of drop: the same 600 channels earned
         * 183 answers when sent back to back and 255 when spaced 20–50 ms apart. A missing session
         * is not an error — the room is in [placement] and the next connect re-subscribes it.
         */
        fun sendFrame(frame: String) {
            // With no session the frame is pointless: everything placed on this connection is
            // re-subscribed wholesale by resubscribeAll() when the next one comes up, and queueing
            // stale frames would spend the new connection's subscribe budget on duplicates.
            if (wsSession == null) return
            if (outbox.trySend(frame).isSuccess) queued.incrementAndGet()
        }

        /** Drains [outbox] at the paced rate. Runs on Dispatchers.IO (see [launchPool]). */
        suspend fun sendLoop() {
            val gapMs = (1000L / subscribeBurstPerSecond).coerceAtLeast(1L)
            for (frame in outbox) {
                val session = wsSession ?: continue
                try {
                    // a write that never completes must not pin the sender forever: drop the frame
                    // and let the (re)connect that follows re-subscribe the room
                    withTimeoutOrNull(sendTimeoutMs) { session.send(Frame.Text(frame)) }
                } catch (e: Exception) {
                    logger.error("WS pool {} failed to send frame: {}", index, e.message)
                } finally {
                    PipelineMetrics.wsPoolSendQueue(index, queued.decrementAndGet())
                }
                delay(gapMs)
            }
        }

        suspend fun connectLoop() {
            while (scope.isActive) {
                val host = wsFailover.currentHost() ?: HostsConfig.DEFAULT_WS_HOST
                var opened = false
                var frames = 0
                try {
                    val token = mintToken()
                    val client = httpClientProvider.proxied("event_$index", http1 = true)
                    client.webSocket(wsUrlBuilder(host)) {
                        wsSession = this
                        opened = true
                        PipelineMetrics.wsPoolConnected(index, true)
                        send(authFrame(token))
                        resubscribeAll()
                        eventBus.publish(WsReconnected)
                        for (frame in incoming) {
                            frames++
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                if (text == "{}") {
                                    send("{}")
                                } else {
                                    dispatch(index, text)
                                }
                            }
                        }
                    }
                    if (frames == 0) {
                        // closed without a single frame — almost certainly an auth rejection
                        // (invalid/expired token). Refetch the token and back off instead of
                        // hot-looping against the platform.
                        wsFailover.markFailure(host)
                        logger.warn(
                            "WS pool {} closed by {} without any frame, retrying in {}ms",
                            index, host, backoff.inWholeMilliseconds
                        )
                        delay(backoff)
                        backoff = minOf(backoff * 2, runtimeTuning.webSocketReconnectMax)
                    } else {
                        wsFailover.markSuccess(host)
                        backoff = runtimeTuning.webSocketReconnectInitial
                    }
                } catch (e: CancellationException) {
                    // A server-side close surfaces as CancellationException from send(); only a
                    // genuine scope cancellation may end the reconnect loop, otherwise a rejected
                    // or dropped connection would kill it forever. A retired pool (see createPools)
                    // cancels just this job, which must exit rather than reconnect.
                    if (!scope.isActive || !currentCoroutineContext().isActive) throw e
                    logger.warn(
                        "WS pool {} closed while connecting to {}: {}, retrying in {}ms",
                        index, host, e.message, backoff.inWholeMilliseconds
                    )
                    delay(backoff)
                    backoff = minOf(backoff * 2, runtimeTuning.webSocketReconnectMax)
                } catch (e: Exception) {
                    wsFailover.markFailure(host)
                    logger.error("WS pool {} error on {}: {}, reconnecting in {}ms", index, host, e.message, backoff.inWholeMilliseconds)
                    delay(backoff)
                    backoff = minOf(backoff * 2, runtimeTuning.webSocketReconnectMax)
                } finally {
                    if (opened) {
                        wsSession = null
                        PipelineMetrics.wsPoolConnected(index, false)
                        eventBus.publish(WsDisconnected)
                    }
                }
            }
        }

        private val sendTimeoutMs = runtimeTuning.webSocketSendTimeout.inWholeMilliseconds

        suspend fun closeSession() {
            try {
                wsSession?.close(CloseReason(CloseReason.Codes.NORMAL, "hosts updated"))
            } catch (e: Exception) {
                logger.debug("ws pool {} close on hosts update: {}", index, e.message)
            }
            wsSession = null
        }

        suspend fun subscribeRoom(roomId: Long, full: Boolean) {
            sendRoomChannels(roomId, full)
        }

        suspend fun downgradeRoom(roomId: Long) {
            sendRoomFullUnsubscribes(roomId)
        }

        suspend fun unsubscribeRoom(roomId: Long) {
            sendRoomUnsubscribes(roomId)
        }

        suspend fun resubscribeAll() {
            // frames queued for the session that just died are superseded by this resubscribe
            while (outbox.tryReceive().isSuccess) {
                PipelineMetrics.wsPoolSendQueue(index, queued.decrementAndGet())
            }
            globalChannels.forEach { sendFrame(subscribeFrame(it)) }
            // read-only view of the assignment: rooms placed on this connection while it was down
            // are picked up here, which is why the connect loop re-runs this on every (re)connect
            placedRooms(index).forEach { roomId ->
                sendRoomChannels(roomId, full = roomId in recordingRooms)
            }
        }
    }

    /** Rooms currently assigned to [poolIdx]. */
    private fun placedRooms(poolIdx: Int): List<Long> =
        placement.entries.filter { it.value.pool == poolIdx }.map { it.key }

    private fun authFrame(token: String): String {
        return """{"connect":{"token":"$token","name":"js"},"id":${seq.incrementAndGet()}}"""
    }

    private fun subscribeFrame(channel: String): String {
        return """{"subscribe":{"channel":"$channel"},"id":${seq.incrementAndGet()}}"""
    }

    private fun unsubscribeFrame(channel: String): String {
        return """{"unsubscribe":{"channel":"$channel"},"id":${seq.incrementAndGet()}}"""
    }

    private suspend fun subscribeRoom(roomId: Long, full: Boolean) {
        subscribed.add(roomId)
        val idx = placeRoom(roomId)
        val pool = pools.getOrNull(idx)
        if (pool == null) {
            logger.warn("roomId={} could not be placed on any WebSocket connection, holding it for the next reconcile", roomId)
            return
        }
        pool.subscribeRoom(roomId, full)
        if (!full) {
            // downgrade: drop the full channel set, keep only the status subset
            pool.downgradeRoom(roomId)
        }
    }

    private suspend fun unsubscribeRoom(roomId: Long) {
        subscribed.remove(roomId)
        recordingRooms.remove(roomId)
        placement.remove(roomId)?.let { placed ->
            pools.getOrNull(placed.pool)?.let { pool ->
                pool.assignedChannels.addAndGet(-placed.channels)
                PipelineMetrics.wsPoolChannels(placed.pool, pool.assignedChannels.get())
                pool.unsubscribeRoom(roomId)
            }
        }
    }

    private suspend fun WsPool.sendRoomChannels(roomId: Long, full: Boolean) {
        val channels = if (full) roomChannels else statusChannels
        // stable sort, so the lifecycle channels go first and the rest keep their declared order
        channels.sortedBy { if (it in lifecycleChannels) 0 else 1 }
            .forEach { channel -> sendFrame(subscribeFrame("$channel@$roomId")) }
    }

    /** Channels needed only while recording; the status subset stays subscribed for tracked rooms. */
    private val recordingOnlyChannels: List<String> = roomChannels - statusChannels.toSet()

    private suspend fun WsPool.sendRoomFullUnsubscribes(roomId: Long) {
        recordingOnlyChannels.forEach { channel -> sendFrame(unsubscribeFrame("$channel@$roomId")) }
    }

    private suspend fun WsPool.sendRoomUnsubscribes(roomId: Long) {
        roomChannels.forEach { channel -> sendFrame(unsubscribeFrame("$channel@$roomId")) }
    }

    /**
     * Counts the subscribe commands the server refused, reporting the first one and then rarely, and
     * naming the connection that was refused so a limit can be attributed to a connection, a token
     * or the whole host.
     *
     * A refusal is a warning with a running count rather than an error per frame, because a
     * saturated connection refuses hundreds in a row: this is the signal that the shard is too
     * heavy, and one line per burst is what makes it actionable instead of an avalanche.
     */
    private fun noteRejectedSubscription(pool: Int, error: JsonObject) {
        val count = rejectedSubscriptions.incrementAndGet()
        PipelineMetrics.wsPoolSubscribeRejected(pool, error["code"]?.jsonPrimitive?.content ?: "unknown")
        if (count == 1 || count % 100 == 0) {
            logger.warn(
                "WebSocket subscribe rejected on pool {} ({} total so far): {}",
                pool, count, error
            )
        }
    }

    private suspend fun dispatch(pool: Int, raw: String) {
        for (line in raw.lines()) {
            if (line.isBlank()) continue
            try {
                val json = Json.parseToJsonElement(line).jsonObject
                // A subscribe the server will not honour comes back as {"id":N,"error":{...}} —
                // "limit exceeded" (code 106) once a connection asks for too many channels. Left
                // unread, the room stays in `subscribed`, gets re-sent on every reconnect and is
                // refused again, and the only symptom is a `.event` sidecar that never gets a byte:
                // exactly the failure this gate exists to make visible.
                json["error"]?.jsonObject?.let { rejection -> noteRejectedSubscription(pool, rejection) }
                val push = json["push"]?.jsonObject ?: continue
                val channel = push["channel"]?.jsonPrimitive?.content ?: continue
                val type = channel.substringBefore("@")
                val roomId = channel.substringAfter("@").toLongOrNull() ?: continue
                val pub = push["pub"]?.jsonObject ?: continue
                val data = pub["data"]?.jsonObject ?: continue

                // Room/model status (public/groupShow/private/virtualPrivate/off/idle...)
                // arrives via two events: broadcastChanged (top-level "status") and
                // modelStatusChanged (nested "model.status").
                if (type == "broadcastChanged" || type == "modelStatusChanged") {
                    val status = data["status"]?.jsonPrimitive?.content
                        ?: data["model"]?.jsonObject?.get("status")?.jsonPrimitive?.content
                        ?: "offline"
                    val oldStatus = roomStatuses[roomId] ?: ""
                    if (status != oldStatus) {
                        logger.debug("WS room/model status: roomId={}, {} -> {}", roomId, oldStatus, status)
                        roomStatuses[roomId] = status
                        eventBus.publish(RoomStatusChanged(roomId, oldStatus, status))
                    }
                }

                // streamChanged carries the STREAM lifecycle status (created/probing/publishing/
                // distributing/finished) — a separate domain from room status.
                if (type == "streamChanged") {
                    val status = data["status"]?.jsonPrimitive?.content ?: ""
                    if (status.isNotEmpty()) {
                        val oldStatus = streamStatuses[roomId] ?: ""
                        if (status != oldStatus) {
                            streamStatuses[roomId] = status
                            eventBus.publish(StreamStatusChanged(roomId, oldStatus, status))
                        }
                    }
                }

                // broadcast-settings / stream changes may alter available qualities — hint the session
                if (type == "broadcastSettingsChanged" || type == "streamChanged") {
                    eventBus.publish(QualityChangeHint(roomId))
                }

                eventBus.publish(LiveMessage(roomId, type, data))
            } catch (e: Exception) { logger.error("Failed to dispatch WS message: ${e.message}", e) }
        }
    }
}
