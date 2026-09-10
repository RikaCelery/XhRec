package github.rikacelery.v3.components

import github.rikacelery.v3.api.ApiClient
import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.data.Hosts
import github.rikacelery.v3.data.Room
import github.rikacelery.v3.data.RoomSettings
import github.rikacelery.v3.data.RoomStatus
import github.rikacelery.v3.data.RuntimeTuning
import github.rikacelery.v3.data.User
import github.rikacelery.v3.events.*
import github.rikacelery.v3.fsm.KEEP
import github.rikacelery.v3.fsm.LoopTimer
import github.rikacelery.v3.fsm.StateMachine
import github.rikacelery.v3.fsm.buildFsm
import github.rikacelery.v3.m3u8.M3u8Parser
import github.rikacelery.v3.m3u8.MasterPlaylist
import github.rikacelery.v3.m3u8.VariantStream
import github.rikacelery.v3.utils.CdnSelector
import github.rikacelery.v3.utils.DefaultHttpClientProvider
import github.rikacelery.v3.utils.HttpClientProvider
import github.rikacelery.v3.utils.PathSingle
import github.rikacelery.v3.utils.PathSingleOrNull
import github.rikacelery.v3.utils.asInt
import github.rikacelery.v3.utils.asString
import github.rikacelery.v3.utils.withRetry
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

// ============================================================
// Scheduler FSM definitions
// ============================================================

enum class SchedulerState { Armed, Preconfiguring, Recording, Stopping }

enum class SchedulerEvent {
    RoomStatusChanged, StreamStatusChanged, ChangeQuality, SettingsChanged, SessionExit,
    PreconfigDone, PreconfigFailed,
    BeginPreconfig, RestartRecording, BackToArmed, StopAndWait,
}

data class SchedulerDriveData(
    val roomStatus: String? = null,
    val streamStatus: String? = null,
    val newQuality: String? = null,
    val lastIndex: Long? = null,
    val exitReason: EndReason? = null,
    val playlistUrl: String? = null,
    val quality: String? = null,
    val token: String? = null,
    val pkey: String? = null,
    val failReason: String? = null,
    val stopReason: EndReason? = null,
)

// ============================================================
// Messages
// ============================================================

sealed interface SchedulerMsg

data class SchedulerBus(val event: Any) : SchedulerMsg

data class SchedulerSignal(val roomId: Long, val event: SchedulerEvent, val data: SchedulerDriveData?) : SchedulerMsg

data class SchedulerHandleCommand(val env: CommandEnvelope) : SchedulerMsg

// ============================================================
// Entry
// ============================================================

private val schedulerLogger = LoggerFactory.getLogger("v3.SchedulerEntry")

internal fun productionMasterUrls(roomId: Long, pkey: String, token: String?): List<String> =
    (listOf(Hosts.current.hlsMasterHost) + Hosts.current.hlsHosts).distinct().map { host ->
        buildUrl {
            protocol = URLProtocol.HTTPS
            this.host = host
            encodedPath = "/hls/$roomId/master/${roomId}_auto.m3u8"
            parameters["psch"] = "v2"
            parameters["pkey"] = pkey
            token?.takeIf { it.isNotEmpty() }?.let { parameters["aclAuth"] = it }
        }.toString()
    }

class SchedulerEntry(
    val roomId: Long,
    var roomName: String,
    internal val component: SchedulerComponent
) {
    // —— Armed configuration ——
    var settings: RoomSettings = RoomSettings()

    // —— Status ——
    var roomStatus: String = ""
    var streamStatus: String = ""
    var currentKind: String = ""       // kind of the current recording: public / groupShow / private

    // —— Preconfiguration results ——
    var playlistUrl: String = ""
    var configuredQuality: String = ""
    var configuredToken: String? = null
    var configuredPkey: String = ""

    // —— Resume state ——
    var lastIndex: Long? = null
    var lastFailReason: String? = null

    val scope = CoroutineScope(
        component.ioScope.coroutineContext + SupervisorJob(component.ioScope.coroutineContext[Job])
    )
    val preconfigLoop = LoopTimer<Unit>(scope)

    val fsm: StateMachine<SchedulerState, SchedulerEvent, SchedulerDriveData, SchedulerEntry> =
        buildSchedulerFsm(this)

    fun launch(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                schedulerLogger.error("Scheduler entry effect failed roomId={}", roomId, e)
            }
        }
    }

    internal fun canRecord(status: String): Boolean = when {
        RoomStatus.isPublic(status) -> settings.recordPublic
        RoomStatus.isGroupShow(status) -> settings.autoPayTicket
        RoomStatus.isPrivate(status) -> settings.autoPaySpy
        else -> false
    }

    internal fun kindOf(status: String): String = when {
        RoomStatus.isPublic(status) -> "public"
        RoomStatus.isGroupShow(status) -> "groupShow"
        RoomStatus.isPrivate(status) -> "private"
        else -> "offline"
    }

    internal fun self(event: SchedulerEvent, data: SchedulerDriveData? = null) {
        launch { component.tell(SchedulerSignal(roomId, event, data)) }
    }

    /** Start the fixed-delay preconfig loop: first attempt immediately, then wait the configured interval. */
    internal fun startPreconfigLoop() {
        preconfigLoop.start(component.runtimeTuning.preconfigRetryInterval) { component.tell(preconfigSignal()) }
    }

    internal fun stopPreconfigLoop() {
        preconfigLoop.cancel()
    }

    internal fun restartRecording() {
        launch {
            component.sessionComponent.tell(
                StartRecording(
                    roomId = roomId,
                    roomName = roomName,
                    playlistUrl = playlistUrl,
                    pkey = configuredPkey,
                    quality = configuredQuality,
                    startIndex = lastIndex?.plus(1),
                    timeLimit = settings.timeLimit,
                    sizeLimitBytes = settings.sizeLimitBytes,
                )
            )
        }
    }

    internal fun stopSession(reason: EndReason) {
        launch { component.sessionComponent.tell(StopRecording(roomId, reason)) }
    }

    // ---- Preconfiguration ----

    internal suspend fun preconfigSignal(): SchedulerMsg {
        return try {
            val config = component.requestBus.request<RoomConfigResponse>(GetRoomConfig(roomId))
            settings = config.settings.copy(pkey = config.settings.pkey.ifBlank { component.streamAuthKey })

            val token = fetchToken(config)
                ?: return SchedulerSignal(roomId, SchedulerEvent.PreconfigFailed, SchedulerDriveData(failReason = lastFailReason ?: "no token"))
            val master = fetchMaster(token)
            val keyName = matchPkey(master)
            val variant = selectVariant(master, settings.quality)
            val url = resolveVariantUrl(variant, keyName, token)
            if (!playlistUsable(url)) {
                return SchedulerSignal(roomId, SchedulerEvent.PreconfigFailed, SchedulerDriveData(failReason = "playlist unusable"))
            }
            SchedulerSignal(
                roomId, SchedulerEvent.PreconfigDone,
                SchedulerDriveData(playlistUrl = url, quality = variant.name, token = token, pkey = keyName)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SchedulerSignal(roomId, SchedulerEvent.PreconfigFailed, SchedulerDriveData(failReason = e.message))
        }
    }

    private suspend fun fetchToken(config: RoomConfigResponse): String? {
        val info = component.apiClient.roomFetchBroadcastInfo(roomName)
        val status = info.PathSingle("item.status").asString()
        roomStatus = status
        return when {
            RoomStatus.isPublic(status) -> ""
            RoomStatus.isGroupShow(status) -> fetchGroupToken(config)
            RoomStatus.isPrivate(status) -> fetchPrivateToken(config)
            else -> {
                lastFailReason = "status $status"
                null
            }
        }
    }

    private suspend fun fetchGroupToken(config: RoomConfigResponse): String? {
        if (!config.settings.autoPayTicket) {
            lastFailReason = "autopay disabled"
            return null
        }
        val camInfo = component.apiClient.roomFetchCamInfo(roomId, "")
        val price = camInfo.PathSingle("user.user.ticketRate").asInt()
        val users = component.requestBus.request<List<User>>(GetValidPaymentAccount(price.toLong()))
        val u = users.firstOrNull()
        if (u == null) {
            lastFailReason = "no account"
            return null
        }
        var token = component.apiClient.roomFetchModelToken(roomId, u)
        if (token == null) {
            component.apiClient.roomRequestGroupShow(roomId, u)
            component.requestBus.request<OkResponse>(DeductCoins(u.userId, price.toLong()))
            delay(1.seconds)
            token = component.apiClient.roomFetchModelToken(roomId, u)
        }
        if (token == null) {
            lastFailReason = "no token"
            return null
        }
        return token
    }

    private suspend fun fetchPrivateToken(config: RoomConfigResponse): String? {
        val users = component.requestBus.request<List<User>>(GetValidPaymentAccount(0))
        val freeUser = users.firstOrNull { component.apiClient.hasFreeSpyAccess(roomId, it) }
        if (freeUser != null) {
            val cam = component.apiClient.roomFetchCamInfo(roomId, freeUser.cookie)
            val token = cam.PathSingle("cam.modelToken").asString().ifBlank { null }
            if (token == null) lastFailReason = "no token"
            return token
        }
        lastFailReason = "no free spy access"
        if (!config.settings.autoPaySpy) {
            lastFailReason = "autopay disabled"
            return null
        }
        val paidUser = users.firstOrNull()
        if (paidUser == null) {
            lastFailReason = "no account"
            return null
        }
        val paidCam = component.apiClient.roomFetchCamInfo(roomId, paidUser.cookie)
        val price = paidCam.PathSingleOrNull("user.user.privateRate")?.asInt()
        if (price == null) {
            lastFailReason = "price unavailable"
            return null
        }
        if (paidUser.coins < price) {
            lastFailReason = "insufficient balance"
            return null
        }
        var token = paidCam.PathSingle("cam.modelToken").asString().ifBlank { null }
        if (token == null) {
            component.apiClient.roomRequestSpyShow(roomId, paidUser)
            for (attempt in 1..4) {
                delay(if (attempt == 1) 500L else 1500L)
                val cam = component.apiClient.roomFetchCamInfo(roomId, paidUser.cookie)
                token = cam.PathSingle("cam.modelToken").asString().ifBlank { null }
                if (token != null) break
            }
        }
        if (token == null) {
            lastFailReason = "no token"
            return null
        }
        return token
    }

    private suspend fun fetchMaster(token: String?): MasterPlaylist {
        val client = component.httpClientProvider.proxied("master_$roomId")
        val urls = component.masterUrlCandidates(roomId, settings.pkey, token)
        var lastErr: Throwable? = null
        for (url in urls) {
            val host = Url(url).host
            val start = System.nanoTime()
            try {
                val response = withRetry(3) { client.get(url) }
                val master = M3u8Parser.parseMaster(response.bodyAsText())
                val durationMs = ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
                CdnSelector.record(host, durationMs)
                return master
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClientRequestException) {
                lastErr = e
                CdnSelector.recordFailure(host)
                schedulerLogger.warn("master playlist business error on {}: {}", host, e.response.status)
            } catch (e: Exception) {
                lastErr = e
                CdnSelector.recordFailure(host)
                schedulerLogger.warn("master playlist fetch failed on {}: {}", host, e.message)
            }
        }
        throw lastErr ?: IllegalStateException("master playlist unavailable")
    }

    private suspend fun matchPkey(master: MasterPlaylist): String {
        val keyIds = master.pschKeys.map { it.substringAfter(":") }
        val match = component.requestBus.request<DecryptKeyMatch>(MatchDecryptKeys(keyIds))
        require(match.decryptKey.isNotEmpty()) {
            "No PSCH key from master playlist matched. keys=$keyIds"
        }
        return match.keyName
    }

    private fun selectVariant(master: MasterPlaylist, requested: String): VariantStream {
        val variant = if (requested == "highest") {
            master.variants.maxByOrNull { it.bandwidth }
        } else {
            val matched = selectQuality(master.variants.map { it.name }, requested)
            master.variants.find { it.name == matched }
        }
        return variant ?: master.variants.maxByOrNull { it.bandwidth }!!
    }

    private fun selectQuality(available: List<String>, requested: String): String {
        if (requested == "highest") return "highest"
        val clean = available.filterNot { it.contains("blurred") }
        if (clean.isEmpty()) return requested
        if (requested in available) return requested
        val reqParts = requested.split("p").filterNot(String::isEmpty)
        val final = clean.minByOrNull { q ->
            val qParts = q.split("p").filterNot(String::isEmpty)
            if (reqParts.size == 2) {
                abs((qParts[0].toIntOrNull() ?: 0) - (reqParts[0].toIntOrNull() ?: 0)) +
                        abs((reqParts[1].toIntOrNull() ?: 30) - (qParts.getOrElse(1) { "30" }.toIntOrNull() ?: 30))
            } else {
                abs((qParts[0].toIntOrNull() ?: 0) - (reqParts[0].toIntOrNull() ?: 0))
            }
        } ?: requested
        return final
    }

    private fun resolveVariantUrl(variant: VariantStream, keyName: String, token: String?): String {
        val url = buildUrl {
            takeFrom(variant.url)
            parameters["psch"] = "v2"
            parameters["pkey"] = keyName
            token?.takeIf { it.isNotEmpty() }?.let { parameters["aclAuth"] = it }
        }.toString()
        return CdnSelector.resolve(url)
    }

    /** Verify the resolved variant playlist is actually fetchable before handing it to the Session. */
    private suspend fun playlistUsable(url: String): Boolean {
        return try {
            withTimeout(5.seconds) {
                val client = component.httpClientProvider.proxied("preconfig_$roomId")
                val response = withRetry(2) { client.get(url) }
                response.status.value in 200..299
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }
}

// ============================================================
// Scheduler FSM matrix
// ============================================================

private fun buildSchedulerFsm(ctx: SchedulerEntry) =
    buildFsm<SchedulerState, SchedulerEvent, SchedulerDriveData, SchedulerEntry>(ctx) {

        initial(SchedulerState.Armed)

        state(SchedulerState.Armed) {
            on(SchedulerEvent.RoomStatusChanged) to KEEP action { d ->
                val st = d?.roomStatus ?: return@action
                roomStatus = st
                if (canRecord(st)) self(SchedulerEvent.BeginPreconfig)
            }
            on(SchedulerEvent.StreamStatusChanged) to KEEP action { d ->
                streamStatus = d?.streamStatus ?: return@action
                if (canRecord(roomStatus) && streamStatus == "distributing") self(SchedulerEvent.BeginPreconfig)
            }
            on(SchedulerEvent.ChangeQuality) to KEEP action { d ->
                if (d?.newQuality != null) settings = settings.copy(quality = d.newQuality)
            }
            on(SchedulerEvent.SessionExit) to KEEP
            on(SchedulerEvent.BeginPreconfig) to SchedulerState.Preconfiguring action { startPreconfigLoop() }
            on(SchedulerEvent.RestartRecording) to KEEP
            on(SchedulerEvent.BackToArmed) to KEEP
            on(SchedulerEvent.StopAndWait) to KEEP
            on(SchedulerEvent.SettingsChanged) to KEEP action { d ->
                val st = d?.roomStatus ?: return@action
                roomStatus = st
                if (canRecord(st)) self(SchedulerEvent.BeginPreconfig)
            }
        }

        state(SchedulerState.Preconfiguring) {
            on(SchedulerEvent.PreconfigDone) to SchedulerState.Recording action { d ->
                preconfigLoop.cancel()
                playlistUrl = d?.playlistUrl ?: return@action
                configuredQuality = d.quality ?: ""
                configuredToken = d.token
                configuredPkey = d.pkey ?: settings.pkey
                currentKind = kindOf(roomStatus)
                lastFailReason = null
                launch {
                    component.sessionComponent.tell(
                        StartRecording(
                            roomId = roomId,
                            roomName = roomName,
                            playlistUrl = playlistUrl,
                            pkey = configuredPkey,
                            quality = configuredQuality,
                            startIndex = lastIndex?.plus(1),
                            timeLimit = settings.timeLimit,
                            sizeLimitBytes = settings.sizeLimitBytes,
                        )
                    )
                }
            }
            on(SchedulerEvent.PreconfigFailed) to KEEP action { d ->
                // stay in Preconfiguring; the 15s ticker retries automatically
                if (lastFailReason != d?.failReason) {
                    schedulerLogger.warn("Preconfig failed room={}: {}", roomId, d?.failReason)
                    lastFailReason = d?.failReason
                }
            }
            on(SchedulerEvent.RoomStatusChanged) to KEEP action { d ->
                if (d?.roomStatus != null) {
                    roomStatus = d.roomStatus
                    if (!canRecord(roomStatus)) self(SchedulerEvent.BackToArmed)
                }
            }
            on(SchedulerEvent.StreamStatusChanged) to KEEP action { d ->
                if (d?.streamStatus != null) streamStatus = d.streamStatus
            }
            on(SchedulerEvent.ChangeQuality) to KEEP action { d ->
                if (d?.newQuality != null) settings = settings.copy(quality = d.newQuality)
            }
            on(SchedulerEvent.BeginPreconfig) to KEEP
            on(SchedulerEvent.BackToArmed) to SchedulerState.Armed action { stopPreconfigLoop() }
            on(SchedulerEvent.RestartRecording) to KEEP
            on(SchedulerEvent.StopAndWait) to KEEP
            on(SchedulerEvent.SettingsChanged) to KEEP action { d ->
                if (d?.roomStatus != null) roomStatus = d.roomStatus
                if (!canRecord(roomStatus)) self(SchedulerEvent.BackToArmed)
            }
        }

        state(SchedulerState.Recording) {
            on(SchedulerEvent.RoomStatusChanged) to KEEP action { d ->
                val st = d?.roomStatus ?: return@action
                val oldKind = currentKind
                roomStatus = st
                if (kindOf(st) != oldKind) {
                    val reason = when {
                        RoomStatus.isOffline(st) -> EndReason.StreamEnd
                        canRecord(st) -> EndReason.StatusChanged
                        else -> EndReason.StreamEnd
                    }
                    self(SchedulerEvent.StopAndWait, SchedulerDriveData(stopReason = reason))
                }
            }
            on(SchedulerEvent.StreamStatusChanged) to KEEP action { d ->
                streamStatus = d?.streamStatus ?: return@action
                if (streamStatus == "finished") {
                    self(SchedulerEvent.StopAndWait, SchedulerDriveData(stopReason = EndReason.StreamEnd))
                }
            }
            on(SchedulerEvent.ChangeQuality) to KEEP action { d ->
                val q = d?.newQuality ?: return@action
                if (q != settings.quality) {
                    settings = settings.copy(quality = q)
                    self(SchedulerEvent.StopAndWait, SchedulerDriveData(stopReason = EndReason.NewInit))
                }
            }
            on(SchedulerEvent.SessionExit) to KEEP action { d ->
                if (component.gracefulStop) {
                    self(SchedulerEvent.BackToArmed)
                    return@action
                }
                lastIndex = d?.lastIndex
                val reason = d?.exitReason ?: EndReason.UserStop
                when (reason) {
                    // a limit cut only resumes the room while it is still recordable: the filter
                    // may have been switched off while this session was running
                    // a limit cut only resumes the room while it is still recordable: the filter
                    // may have been switched off while this session was running
                    EndReason.TimeLimit, EndReason.SizeLimit ->
                        if (canRecord(roomStatus)) self(SchedulerEvent.RestartRecording)
                        else self(SchedulerEvent.BackToArmed)
                    else -> {
                        if (canRecord(roomStatus)) self(SchedulerEvent.BeginPreconfig)
                        else self(SchedulerEvent.BackToArmed)
                    }
                }
            }
            on(SchedulerEvent.RestartRecording) to KEEP action { restartRecording() }
            on(SchedulerEvent.BeginPreconfig) to SchedulerState.Preconfiguring action { startPreconfigLoop() }
            on(SchedulerEvent.BackToArmed) to SchedulerState.Armed action {
                currentKind = ""
                lastIndex = null
            }
            on(SchedulerEvent.StopAndWait) to SchedulerState.Stopping action { d ->
                stopSession(d?.stopReason ?: EndReason.UserStop)
            }
            on(SchedulerEvent.PreconfigDone) to KEEP
            on(SchedulerEvent.PreconfigFailed) to KEEP
            on(SchedulerEvent.SettingsChanged) to KEEP action { d ->
                if (d?.roomStatus != null) roomStatus = d.roomStatus
                if (!canRecord(roomStatus)) {
                    self(SchedulerEvent.StopAndWait, SchedulerDriveData(stopReason = EndReason.StatusChanged))
                }
            }
        }

        state(SchedulerState.Stopping) {
            on(SchedulerEvent.SessionExit) to KEEP action { d ->
                if (component.gracefulStop) {
                    self(SchedulerEvent.BackToArmed)
                    return@action
                }
                lastIndex = d?.lastIndex
                val reason = d?.exitReason ?: EndReason.UserStop
                when (reason) {
                    EndReason.TimeLimit, EndReason.SizeLimit ->
                        if (canRecord(roomStatus)) self(SchedulerEvent.RestartRecording)
                        else self(SchedulerEvent.BackToArmed)
                    EndReason.NewInit -> self(SchedulerEvent.BeginPreconfig)
                    EndReason.StatusChanged -> {
                        if (canRecord(roomStatus)) self(SchedulerEvent.BeginPreconfig)
                        else self(SchedulerEvent.BackToArmed)
                    }
                    else -> self(SchedulerEvent.BackToArmed)
                }
            }
            on(SchedulerEvent.RestartRecording) to SchedulerState.Recording action { restartRecording() }
            on(SchedulerEvent.BeginPreconfig) to SchedulerState.Preconfiguring action { startPreconfigLoop() }
            on(SchedulerEvent.BackToArmed) to SchedulerState.Armed action {
                currentKind = ""
                lastIndex = null
            }
            on(SchedulerEvent.RoomStatusChanged) to KEEP action { d ->
                if (d?.roomStatus != null) roomStatus = d.roomStatus
            }
            on(SchedulerEvent.StreamStatusChanged) to KEEP action { d ->
                if (d?.streamStatus != null) streamStatus = d.streamStatus
            }
            on(SchedulerEvent.ChangeQuality) to KEEP action { d ->
                if (d?.newQuality != null) settings = settings.copy(quality = d.newQuality)
            }
            on(SchedulerEvent.StopAndWait) to KEEP
            on(SchedulerEvent.PreconfigDone) to KEEP
            on(SchedulerEvent.PreconfigFailed) to KEEP
            on(SchedulerEvent.SettingsChanged) to KEEP action { d ->
                if (d?.roomStatus != null) roomStatus = d.roomStatus
            }
        }
    }

// ============================================================
// Actor
// ============================================================

class SchedulerComponent(
    internal val requestBus: RequestBus,
    internal val sessionComponent: SessionComponent,
    internal val apiClient: ApiClient,
    internal val streamAuthKey: String,
    eventBus: EventBus,
    parentScope: CoroutineScope,
    internal val httpClientProvider: HttpClientProvider = DefaultHttpClientProvider,
    internal val runtimeTuning: RuntimeTuning = RuntimeTuning(),
    internal val masterUrlCandidates: (Long, String, String?) -> List<String> = ::productionMasterUrls
) : Actor<SchedulerMsg>("SchedulerComponent", eventBus, parentScope) {

    private val entries = ConcurrentHashMap<Long, SchedulerEntry>()
    internal var gracefulStop = false

    override suspend fun onStart(scope: CoroutineScope) {
        subscribe<RoomStatusChanged>(RoomStatusChanged::class)
        subscribe<StreamStatusChanged>(StreamStatusChanged::class)
        subscribe<RoomRemoved>(RoomRemoved::class)
        subscribe<SessionExit>(SessionExit::class)
        subscribe<QualityChangeRequested>(QualityChangeRequested::class)
        subscribe<RoomSettingsChanged>(RoomSettingsChanged::class)
        subscribe<CommandEnvelope>(CommandEnvelope::class)
        subscribe<WriterFatal>(WriterFatal::class)
        subscribe<AuthExpired>(AuthExpired::class)
        subscribe<StopEvent>(StopEvent::class)
    }

    override suspend fun wrapEvent(event: Any): SchedulerMsg? = when (event) {
        is RoomStatusChanged -> SchedulerBus(event)
        is StreamStatusChanged -> SchedulerBus(event)
        is RoomRemoved -> SchedulerBus(event)
        is SessionExit -> SchedulerBus(event)
        is QualityChangeRequested -> SchedulerBus(event)
        is RoomSettingsChanged -> SchedulerBus(event)
        is CommandEnvelope -> SchedulerHandleCommand(event)
        is WriterFatal -> SchedulerBus(event)
        is AuthExpired -> SchedulerBus(event)
        is StopEvent -> SchedulerBus(event)
        else -> null
    }

    override suspend fun handle(msg: SchedulerMsg) {
        when (msg) {
            is SchedulerBus -> onBus(msg.event)
            is SchedulerSignal -> driveFsm(msg)
            is SchedulerHandleCommand -> handleCommand(msg.env)
        }
    }

    private suspend fun onBus(event: Any) {
        when (event) {
            is RoomStatusChanged -> driveFsm(event.roomId, SchedulerEvent.RoomStatusChanged, SchedulerDriveData(roomStatus = event.newStatus))
            is StreamStatusChanged -> driveFsm(event.roomId, SchedulerEvent.StreamStatusChanged, SchedulerDriveData(streamStatus = event.newStatus))
            is SessionExit -> driveFsm(event.roomId, SchedulerEvent.SessionExit, SchedulerDriveData(lastIndex = event.lastIndex, exitReason = event.reason))
            is QualityChangeRequested -> driveFsm(event.roomId, SchedulerEvent.ChangeQuality, SchedulerDriveData(newQuality = event.newQuality))
            is RoomSettingsChanged -> {
                // mirror what the entry would have read on its next preconfig attempt, then let
                // the FSM decide whether the new settings mean start, stop or carry on
                entries[event.roomId]?.let { entry ->
                    entry.settings = event.settings.copy(
                        pkey = event.settings.pkey.ifBlank { streamAuthKey }
                    )
                    driveFsm(
                        event.roomId,
                        SchedulerEvent.SettingsChanged,
                        SchedulerDriveData(roomStatus = event.status)
                    )
                }
            }
            is WriterFatal -> {
                logger.error("Writer fatal room {}: {}", event.roomId, event.error)
                entries.remove(event.roomId)?.scope?.cancel()
            }
            // A room removed from the list while armed/recording must be disarmed and its
            // recording stopped, otherwise the session keeps writing and the UI shows a
            // ghost row (see issue #141). Mirrors the DeactivateCmd branch.
            is RoomRemoved -> {
                entries.remove(event.roomId)?.scope?.cancel()
                sessionComponent.tell(StopRecording(event.roomId, EndReason.UserStop))
                logger.info("Room {} removed: disarmed and recording stopped", event.roomId)
            }
            is AuthExpired -> logger.warn("Auth expired user {}", event.userId)
            is StopEvent -> {
                if (gracefulStop) return
                gracefulStop = true
                logger.info("Stop event received: scheduler will not start new recordings")
                entries.values.forEach { e ->
                    when (e.fsm.currentState) {
                        SchedulerState.Armed, SchedulerState.Preconfiguring -> e.scope.cancel()
                        else -> {}   // Recording/Stopping sessions stop themselves
                    }
                }
            }
        }
    }

    private suspend fun driveFsm(sig: SchedulerSignal) {
        val e = entries[sig.roomId] ?: return
        e.fsm.driveCatch(sig.event, sig.data)?.let { logger.error("Scheduler FSM drive failed", it) }
    }

    private suspend fun driveFsm(roomId: Long, event: SchedulerEvent, data: SchedulerDriveData?) {
        val e = entries[roomId] ?: return
        e.fsm.driveCatch(event, data)?.let { logger.error("Scheduler FSM drive failed", it) }
    }

    /** Arms a room loaded from list.conf and returns its entry, or null when it is not armed. */
    fun internalAdd(room: Long, name: String, settings: RoomSettings, isArmed: Boolean): SchedulerEntry? {
        if (!isArmed) return null
        val entry = entries.getOrPut(room) {
            SchedulerEntry(room, name, this).apply {
                this.settings = settings.copy(pkey = settings.pkey.ifBlank { streamAuthKey })
            }
        }
        logger.info("Room {} ({}) armed and waiting", name, room)
        return entry
    }

    private suspend fun handleCommand(env: CommandEnvelope) {
        val ack = when (val cmd = env.command) {
            is ActivateRecordingCmd -> {
                try {
                    val name = requestBus.request<RoomNameResponse>(GetRoomName(cmd.roomId)).name
                    val config = requestBus.request<RoomConfigResponse>(GetRoomConfig(cmd.roomId))
                    entries.getOrPut(cmd.roomId) {
                        SchedulerEntry(cmd.roomId, name, this).apply {
                            settings = config.settings.copy(
                                pkey = config.settings.pkey.ifBlank { streamAuthKey }
                            )
                        }
                    }
                    logger.info("Room {} ({}) activated (armed)", name, cmd.roomId)
                    requestBus.request<OkResponse>(RefreshRoomCmd(cmd.roomId))
                    // Arming a room that is already recordable must not wait for the next
                    // status event: the refresh above only publishes RoomStatusChanged when
                    // the status actually changes, so an already-public room would sit armed
                    // until something else moved. Feed the current status into the FSM.
                    val currentStatus = requestBus.request<List<Room>>(GetRooms)
                        .firstOrNull { it.id == cmd.roomId }?.status.orEmpty()
                    if (currentStatus.isNotEmpty() && entries[cmd.roomId]?.canRecord(currentStatus) == true) {
                        driveFsm(
                            cmd.roomId,
                            SchedulerEvent.RoomStatusChanged,
                            SchedulerDriveData(roomStatus = currentStatus)
                        )
                    }
                } catch (_: Exception) {
                }
                OkResponse
            }

            is DeactivateCmd -> {
                entries.remove(cmd.roomId)?.scope?.cancel()
                sessionComponent.tell(StopRecording(cmd.roomId, EndReason.UserStop))
                logger.info("Room {} deactivated", cmd.roomId)
                OkResponse
            }

            is BreakCmd -> {
                sessionComponent.tell(StopRecording(cmd.roomId, cmd.reason))
                OkResponse
            }

            is GetArmedRoomIds -> entries.keys().toList()

            is ShutdownCmd -> {
                gracefulStop = true
                entries.forEach { (id, e) ->
                    sessionComponent.tell(StopRecording(id, EndReason.UserStop))
                    e.scope.cancel()
                }
                entries.clear()
                OkResponse
            }

            else -> return
        }
        eventBus.publish(CommandAck(env.id, ack))
    }
}
