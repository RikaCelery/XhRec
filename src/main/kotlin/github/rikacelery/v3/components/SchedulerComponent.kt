package github.rikacelery.v3.components

import github.rikacelery.v3.api.ApiClient
import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.BusMonitor
import github.rikacelery.v3.core.Diagnosable
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.core.diagnose
import github.rikacelery.v3.data.Hosts
import github.rikacelery.v3.data.Room
import github.rikacelery.v3.data.RoomHint
import github.rikacelery.v3.data.RoomHintCode
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
import kotlinx.coroutines.withTimeoutOrNull
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

/**
 * Why a token could not be obtained. Decisions read this instead of parsing
 * [SchedulerDriveData.failReason], which stays a human-readable log message.
 */
enum class TokenFailure {
    AutopayDisabled, NoAccount, PriceUnavailable, InsufficientBalance, NoToken, NoFreeSpy, BadStatus
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
    val tokenFailure: TokenFailure? = null,
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

    /**
     * Set when a private show could not use a free spy privilege and paid spy is off. The
     * privilege is a property of the account, not of the show, so one probe per private
     * episode is enough — without this the preconfig loop would ask the platform every
     * [RuntimeTuning.preconfigRetryInterval] while the show lasts.
     */
    var freeSpyExhausted: Boolean = false

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
    /**
     * The last media segment id this room recorded, used as the resume mark for the *next*
     * session of the *same* stream. It is passed to the session as `startIndex`, where anything
     * with a segment id at or below it is skipped so a cut never re-downloads what was just
     * written.
     *
     * The platform's segment ids restart with each broadcast, so this mark is only meaningful
     * while one stream keeps running. Carrying it across a `BeginPreconfig` (the stream is
     * resolved from scratch) or a `BackToArmed` would compare a fresh broadcast's low ids against
     * a previous broadcast's high mark and silently skip *every* segment — the session then
     * reports `Recording` while downloading nothing until the counter climbs past the stale mark.
     * Every path that re-resolves the stream therefore clears it; only `RestartRecording`
     * (a time/size limit cut inside one stream) keeps it.
     */
    var lastIndex: Long? = null
    var lastFailReason: String? = null
    private var tokenFailure: TokenFailure? = null

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
        // a free spy privilege is worth recording on its own; paying still needs autoPaySpy
        RoomStatus.isPrivate(status) -> settings.autoPaySpy ||
            (settings.recordFreeSpy && !freeSpyExhausted)
        else -> false
    }

    /**
     * Why an armed room is not recording, or null when there is nothing to explain: the session
     * is running, the show is simply not on, or recording is about to start. The dashboard shows
     * it next to the room status, so a room that is waiting for the room status to change can be
     * told apart from one that was switched off or cannot get a token.
     */
    fun hint(): RoomHint? {
        if (fsm.currentState == SchedulerState.Recording) return null
        return when {
            RoomStatus.isPublic(roomStatus) && !settings.recordPublic ->
                RoomHint(RoomHintCode.PUBLIC_FILTER_OFF)

            RoomStatus.isGroupShow(roomStatus) && !settings.autoPayTicket ->
                RoomHint(RoomHintCode.TICKET_PURCHASE_OFF)

            RoomStatus.isPrivate(roomStatus) && !settings.autoPaySpy && freeSpyExhausted ->
                RoomHint(RoomHintCode.NO_FREE_SPY)

            RoomStatus.isPrivate(roomStatus) && !settings.autoPaySpy && !settings.recordFreeSpy ->
                RoomHint(RoomHintCode.PRIVATE_FILTER_OFF)

            fsm.currentState == SchedulerState.Preconfiguring && lastFailReason != null ->
                RoomHint(RoomHintCode.PRECONFIG_FAILED, lastFailReason)

            else -> null
        }
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
            tokenFailure = null

            val token = fetchToken(config)
                ?: return SchedulerSignal(
                    roomId,
                    SchedulerEvent.PreconfigFailed,
                    SchedulerDriveData(failReason = lastFailReason ?: "no token", tokenFailure = tokenFailure)
                )
            val master = fetchMaster(token)
            val keyName = matchPkey(master)
            val variant = selectVariant(master, settings.quality)
            val url = resolveVariantUrl(variant, keyName, token)
            playlistProbe(url)?.let { reason ->
                return SchedulerSignal(roomId, SchedulerEvent.PreconfigFailed, SchedulerDriveData(failReason = reason))
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
                tokenFailure = TokenFailure.BadStatus
                null
            }
        }
    }

    private suspend fun fetchGroupToken(config: RoomConfigResponse): String? {
        if (!config.settings.autoPayTicket) {
            lastFailReason = "autopay disabled"
            tokenFailure = TokenFailure.AutopayDisabled
            return null
        }
        val camInfo = component.apiClient.roomFetchCamInfo(roomId, "")
        val price = camInfo.PathSingle("user.user.ticketRate").asInt()
        val users = component.requestBus.request<List<User>>(GetValidPaymentAccount(price.toLong()))
        val u = users.firstOrNull()
        if (u == null) {
            lastFailReason = "no account"
            tokenFailure = TokenFailure.NoAccount
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
            tokenFailure = TokenFailure.NoToken
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
            if (token == null) {
                lastFailReason = "no token"
                tokenFailure = TokenFailure.NoToken
            }
            return token
        }
        lastFailReason = "no free spy access"
        tokenFailure = TokenFailure.NoFreeSpy
        if (!config.settings.autoPaySpy) {
            lastFailReason = "autopay disabled"
            return null
        }
        val paidUser = users.firstOrNull()
        if (paidUser == null) {
            lastFailReason = "no account"
            tokenFailure = TokenFailure.NoAccount
            return null
        }
        val paidCam = component.apiClient.roomFetchCamInfo(roomId, paidUser.cookie)
        val price = paidCam.PathSingleOrNull("user.user.privateRate")?.asInt()
        if (price == null) {
            lastFailReason = "price unavailable"
            tokenFailure = TokenFailure.PriceUnavailable
            return null
        }
        if (paidUser.coins < price) {
            lastFailReason = "insufficient balance"
            tokenFailure = TokenFailure.InsufficientBalance
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
            tokenFailure = TokenFailure.NoToken
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

    /**
     * Verify the resolved variant playlist is actually fetchable before handing it to the Session.
     *
     * A probe that outlives the watchdog is a *failed probe*, not a cancellation of the caller:
     * [withTimeoutOrNull] consumes the timeout so the answer stays a reason and the preconfig loop
     * can retry. Letting the timeout escape as a `CancellationException` (which the catch below
     * rethrows, as [SchedulerEntry.preconfigSignal] does) silently cancelled that loop, leaving
     * the room stuck in `Preconfiguring` with no log, no hint and no retry.
     *
     * Returns `null` when the playlist is fetchable, or a human-readable reason when it is not.
     * Note this deliberately checks *reachability only*: whether the playlist carries media
     * segments is a property of the live stream that changes second to second, so rejecting on it
     * here would refuse rooms that are about to be recordable. A stream that stops delivering is
     * caught by the session's own stall watchdog instead.
     */
    private suspend fun playlistProbe(url: String): String? {
        // The probe reports success as an *empty string*, not null: `withTimeoutOrNull` also yields
        // null, so a null-able "ok" would be indistinguishable from a probe that ran out of time.
        val reason: String? = try {
            withTimeoutOrNull(component.runtimeTuning.preconfigProbeTimeout) {
                val client = component.httpClientProvider.proxied("preconfig_$roomId")
                val response = withRetry(2) { client.get(url) }
                if (response.status.value in 200..299) "" else "playlist unusable (HTTP ${response.status.value})"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "playlist unusable (${e.message ?: e::class.simpleName})"
        }
        return when {
            reason == null -> "playlist unusable (probe timed out)"
            reason.isEmpty() -> null
            else -> reason
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
            // Entering preconfig means the stream is (re)resolved from scratch, so a resume mark
            // from an earlier recording is meaningless here — see the note on `lastIndex`.
            on(SchedulerEvent.BeginPreconfig) to SchedulerState.Preconfiguring action {
                lastIndex = null
                startPreconfigLoop()
            }
            on(SchedulerEvent.RestartRecording) to KEEP
            on(SchedulerEvent.BackToArmed) to KEEP action { lastIndex = null }
            on(SchedulerEvent.StopAndWait) to KEEP
            // A preconfig attempt runs off the actor loop, so its answer can already be in the
            // mailbox when BackToArmed cancels the loop. The room is armed because it is no longer
            // recordable (or was restarted), so the late answer is dropped instead of being an
            // illegal transition.
            on(SchedulerEvent.PreconfigDone) to KEEP
            on(SchedulerEvent.PreconfigFailed) to KEEP
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
                // no free spy privilege and paid spy is off: this room is not recordable while the
                // private show lasts, so stop asking the platform every retry interval
                if (d?.tokenFailure == TokenFailure.NoFreeSpy && !settings.autoPaySpy) {
                    freeSpyExhausted = true
                    schedulerLogger.info(
                        "Room {}: no free spy access, waiting for the next private show", roomId
                    )
                    self(SchedulerEvent.BackToArmed)
                    return@action
                }
                // stay in Preconfiguring; the ticker retries automatically
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
            on(SchedulerEvent.BeginPreconfig) to KEEP action { lastIndex = null }
            on(SchedulerEvent.BackToArmed) to SchedulerState.Armed action {
                lastIndex = null
                stopPreconfigLoop()
            }
            on(SchedulerEvent.RestartRecording) to KEEP
            on(SchedulerEvent.StopAndWait) to KEEP
            // No session of this entry is running yet — recording only starts on PreconfigDone — so
            // an exit surfacing here belongs to an earlier incarnation of the room (the session the
            // dashboard stopped before re-activating it). It carries nothing to act on, and taking
            // its lastIndex would restart the next recording at the wrong segment.
            on(SchedulerEvent.SessionExit) to KEEP
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
            on(SchedulerEvent.BeginPreconfig) to SchedulerState.Preconfiguring action {
                lastIndex = null
                startPreconfigLoop()
            }
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
            on(SchedulerEvent.BeginPreconfig) to SchedulerState.Preconfiguring action {
                lastIndex = null
                startPreconfigLoop()
            }
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
            is RoomStatusChanged -> {
                // the free spy privilege is probed once per private show, so anything that ends
                // the show (offline, public, group show) re-arms the probe for the next one
                if (!RoomStatus.isPrivate(event.newStatus)) entries[event.roomId]?.freeSpyExhausted = false
                driveFsm(event.roomId, SchedulerEvent.RoomStatusChanged, SchedulerDriveData(roomStatus = event.newStatus))
            }
            is StreamStatusChanged -> driveFsm(event.roomId, SchedulerEvent.StreamStatusChanged, SchedulerDriveData(streamStatus = event.newStatus))
            is SessionExit -> driveFsm(event.roomId, SchedulerEvent.SessionExit, SchedulerDriveData(lastIndex = event.lastIndex, exitReason = event.reason))
            is QualityChangeRequested -> driveFsm(event.roomId, SchedulerEvent.ChangeQuality, SchedulerDriveData(newQuality = event.newQuality))
            is RoomSettingsChanged -> {
                // mirror what the entry would have read on its next preconfig attempt, then let
                // the FSM decide whether the new settings mean start, stop or carry on
                entries[event.roomId]?.let { entry ->
                    entry.settings = event.settings.copy(
                        // quality travels as QualityChangeRequested: it restarts a running session
                        // by comparing the requested value with the one held here
                        quality = entry.settings.quality,
                        pkey = event.settings.pkey.ifBlank { streamAuthKey }
                    )
                    entry.freeSpyExhausted = false
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

            is GetRecordingHints -> RecordingHintsResponse(
                entries.mapNotNull { (roomId, entry) -> entry.hint()?.let { roomId to it } }.toMap()
            )

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

    // —— Diagnostics ——

    override val diagnoseSections: List<String>
        get() = listOf(Diagnosable.SECTION_SUMMARY, Diagnosable.SECTION_ENTRIES, Diagnosable.SECTION_HISTORY)

    /**
     * `/diagnose?actor=SchedulerComponent[&section=entries|history][&room=<id>]`
     *
     * A room stuck mid-pipeline is usually explained by its transition history — e.g. a resume
     * mark (`lastIndex`) carried across a stream change — so that is reported next to the state.
     */
    override suspend fun diagnose(section: String, args: Map<String, String>): JsonObject {
        val roomFilter = args["room"]?.toLongOrNull()
        val selected = entries.values
            .filter { roomFilter == null || it.roomId == roomFilter }
            .sortedBy { it.roomId }
        return baseDiagnose(buildJsonObject {
            put("gracefulStop", gracefulStop)
            put("roomCount", entries.size)
            put("states", buildJsonObject {
                entries.forEach { (id, e) -> put(id.toString(), JsonPrimitive(e.fsm.currentState.toString())) }
            })
            if (section == Diagnosable.SECTION_HISTORY) {
                put("history", buildJsonObject {
                    selected.forEach { put(it.roomId.toString(), it.fsm.diagnose()["history"] ?: JsonArray(emptyList())) }
                })
            } else {
                put("entries", buildJsonArray { selected.forEach { add(it.diagnoseJson()) } })
            }
        })
    }
}

/**
 * One scheduler entry as an operator needs to see it. URLs are reduced to their path and the
 * configured token is never emitted: this view is served over HTTP and must not leak credentials.
 */
internal fun SchedulerEntry.diagnoseJson(): JsonObject = buildJsonObject {
    put("roomId", roomId)
    put("roomName", roomName)
    put("roomStatus", roomStatus)
    put("streamStatus", streamStatus)
    put("kind", currentKind)
    put("freeSpyExhausted", freeSpyExhausted)
    put("lastIndex", lastIndex?.let { JsonPrimitive(it) } ?: JsonNull)
    put("lastFailReason", lastFailReason?.let { JsonPrimitive(it) } ?: JsonNull)
    put("playlistPath", JsonPrimitive(playlistUrl.substringBefore('?')))
    put("configuredQuality", configuredQuality)
    put("configuredPkey", configuredPkey)
    put("preconfigLoopRunning", preconfigLoop.isRunning)
    put("settings", buildJsonObject {
        put("quality", settings.quality)
        put("recordPublic", settings.recordPublic)
        put("recordFreeSpy", settings.recordFreeSpy)
        put("autoPayTicket", settings.autoPayTicket)
        put("autoPaySpy", settings.autoPaySpy)
    })
    put("fsm", fsm.diagnose())
}
