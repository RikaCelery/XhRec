package github.rikacelery.v2

import github.rikacelery.Event
import github.rikacelery.Room
import github.rikacelery.UserManager
import github.rikacelery.utils.*
import github.rikacelery.v2.metric.Metric
import github.rikacelery.v2.metric.MetricItem
import github.rikacelery.v2.metric.MetricUpdater
import github.rikacelery.v2.postprocessors.PostProcessor
import github.rikacelery.v2.postprocessors.ProcessorCtx
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import okhttp3.internal.toLongOrDefault
import org.slf4j.LoggerFactory
import java.io.File
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

class Session(

    private val room: Room,
    private val dest: String,
    private val tmp: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        val DECRYPT_KEY = String(
            String(
                Base64.getDecoder().decode("NTEgNzUgNjUgNjEgNmUgMzQgNjMgNjEgNjkgMzkgNjIgNmYgNGEgNjEgMzUgNjE=")
            ).split(" ").map { it.toByte(16) }.toByteArray()
        )
        val DECRYPT_KEY_V2 = String(
            String(
                Base64.getDecoder().decode("NDUgNTEgNzUgNjUgNjUgNDcgNjggMzIgNmIgNjEgNjUgNzcgNjEgMzMgNjMgNjg=")
            ).split(" ").map { it.toByte(16) }.toByteArray()
        )
        val AUTH_KEY = String(
            String(
                Base64.getDecoder().decode("NWEgNmYgNmIgNjUgNjUgMzIgNGYgNjggNTAgNjggMzkgNmIgNzUgNjcgNjggMzQ=")
            ).split(" ").map { it.toByte(16) }.toByteArray()
        )
        val AUTH_KEY_V2 = String(
            String(
                Base64.getDecoder().decode("NGYgNmYgNmIgMzcgNzEgNzUgNjEgNjkgNGUgNjcgNjkgNzkgNzUgNjggNjEgNjk=")
            ).split(" ").map { it.toByte(16) }.toByteArray()
        )

        private val logger = LoggerFactory.getLogger(Session::class.java)
    }

    private var shouldStop = false
    private val scope =
        CoroutineScope(dispatcher + SupervisorJob() + CoroutineExceptionHandler { coroutineContext, throwable ->
            logger.error("Exception in {}", coroutineContext, throwable)
        })

    private val _status = AtomicReference("")
    private val _isActive = AtomicBoolean(false)
    val isActive: Boolean get() = _isActive.get()
    val status: String get() = _status.get()
    var currentQuality = room.quality

    private val writerReference = AtomicReference<Writer?>(null)
    private var generatorJob: Job? = null

    private val runningUrl = Hashtable<String, UrlInfo>()
    private val replacedUrl = Hashtable<String, Boolean>()

    @Serializable
    enum class ClientType {
        DIRECT, PROXY
    }

    @Serializable
    data class UrlInfo(val type: ClientType, val startAt: Long)

    @Serializable
    data class Status(
        val total: Int, val success: Int, val failed: Int, val bytesWrite: Long, val running: Map<String, UrlInfo>
    )

    fun status(): Status {
        synchronized(runningUrl) {
            val data = metric.data ?: MetricItem()
            return Status(
                data.total,
                data.successDirect + data.successProxied,
                data.failed,
                data.bytesWrite,
                runningUrl.toMap().mapKeys {
                    if (replacedUrl[it.key] == true) {
                        it.key.replace(regexCache) { result ->
                            "${result.groupValues[1]}.doppiocdn.live"
                        }
                    } else {
                        it.key
                    }
                })
        }
    }

    suspend fun updateInfo(): JsonObject {
        // fetch room info
        val info = API.roomFetchBroadcastInfo(room)
        val status = info.PathSingle("item.status").asString()
        _status.set(status)
        return info
    }

    suspend fun testAndConfigure(): Boolean {
        try {
            val info = updateInfo()
            // room status check
            when (status) {
                "public" -> {}
                "groupShow" -> {

                    val price = API.roomFetchCamInfo(room)
                        .PathSingle("user.user.ticketRate").asInt()
                    if (!room.autoPay) {
                        logger.warn("[{}] Room not enable autopay. price={}", room.name, price)
                        return false
                    }
                    val u = UserManager.validPaymentAccount(price)
                    if (u == null) {
                        logger.warn("[{}] No account to pay. price={}", room.name, price)
                        return false
                    }
                    // TODO check cookie and coins status
                    val token = API.roomFetchModelToken(room, u) ?: run {
                        API.roomRequestGroupShow(room, u)
                        UserManager.update(
                            u.copy(
                                coins = u.coins - price
                            )
                        )
                        delay(1000)
                        API.roomFetchModelToken(room, u)
                    }
                    if (token == null) {
                        logger.warn("[{}] Failed to get model token.", room.name)
                        return false
                    }
                    modelToken = token
                }
//
//                "private" -> {
//                    logger.trace("[{}] -> false, status={}", room.name, status)
//                    return false
//                }
//
//                "idle" -> {
//                    logger.trace("[{}] -> false, status={}", room.name, status)
//                    return false
//                }
//
//                "p2p" -> {
//                    logger.trace("[{}] -> false, status={}", room.name, status)
//                    return false
//                }
//
//                "off" -> {
//                    logger.trace("[{}] -> false, status={}", room.name, status)
//                    return false
//                }
//
                else -> {
                    logger.trace("[{}] -> false, status={}", room.name, status)
                    return false
                }
            }

            // quality setting
            if (room.quality == "raw") {
                logger.trace("[{}] -> true, skip quality selection for 'raw'", room.name)
                return true
            }

            val presets = info.PathSingle("item.settings.presets").jsonArray
            val qualities = presets.map { element -> element.asString() }
                .filterNot { it.contains("blurred") }
            val q = qualities.lastOrNull { it == room.quality } ?: qualities.minByOrNull {
                val split = it.split("p").filterNot(String::isEmpty)
                val split1 = room.quality.split("p").filterNot(String::isEmpty)
                if (split1.size == 2) {
                    // 尝试获取帧率和清晰度都接近的
                    abs(split[0].toInt() - split1[0].toInt()) + abs(split1[1].toInt() - split.getOrElse(1) { "30" }
                        .toInt())
                } else {
                    // 只判断清晰度，选到什么纯看运气
                    abs(split[0].toInt() - split1[0].toInt())
                }
            }
            val new = q ?: "raw" // 只有一种清晰度的
            logger.trace("[{}] select {} from {} (want {})", room.name, new, qualities, room.quality)
            if (currentQuality != new && !isActive) {
                logger.info("[{}] quality changed to {} (want {})", room.name, new, room.quality)
                currentQuality = new
            }
            logger.trace("[{}] -> true", room.name)
            return true
        } catch (e: ClientRequestException) {
            logger.warn("Room configure failed.", e)
        }
        logger.warn("[{}] Failed to check room state", room.name)
        logger.trace("[{}] -> true", room.name)
        return false
    }

    private fun createDiscontinuityCounter(): suspend (List<Int>) -> Int {
        var lastMax: Int? = null
        var totalGaps = 0
        val lock = Mutex()
        return counter@{ numbers: List<Int> ->
            lock.withLock {
                if (numbers.isEmpty()) return@counter totalGaps

                val currentMin = numbers.first()      // 连续递增，首项最小
                val currentMax = numbers.last()       // 尾项最大

                if (lastMax != null) {
                    val gap = currentMin - lastMax!! - 1
                    if (gap > 0) {
                        totalGaps += gap
                    }
                    // 如果 gap <= 0，说明重叠或紧接，无新增不连续
                }

                lastMax = currentMax  // 更新状态
                totalGaps
            }
        }
    }

    private fun segmentIDFromUrl(url: String): Int? {
//        roomid_480p_h265_SEGMENTID_XXXXXXXXXXX_timestamp.mp4 transcended stream
//        roomid_SEGMENTID_XXXXXXXXXXX_timestamp.mp4 raw stream
        val parts = url.substringAfterLast("/").split("_")
        if (parts.size == 6)
            return parts[3].toIntOrNull()
        else if (parts.size == 4)
            return parts[1].toIntOrNull()
        return null
    }

    private var metric: MetricUpdater = Metric.newMetric(room.id, room.name)
    suspend fun start() {
        if (!_isActive.compareAndSet(false, true)) {
            throw IllegalStateException("Session is already active")
        }
        logger.info(
            "[+] start recording {}({}) q:{}(want {}) open:{}",
            room.name,
            room.id,
            currentQuality,
            room.quality,
            status
        )
        shouldStop = false
        writerReference.set(Writer(room.name, dest, tmp).apply { init() })
        val counter = createDiscontinuityCounter()
        metric = Metric.newMetric(room.id, room.name)

        try {
            generatorJob = scope.launch {
                var lastInitSegment = byteArrayOf()
                segmentGenerator()
                    .map { event ->
                        if (event is Event.CmdFinish) {
                            return@map (event to async { Result.success(byteArrayOf()) })
                        }
                        if (event is Event.WSEvent) {
                            return@map (event to async { Result.success(byteArrayOf()) })
                        }
                        val index = segmentIDFromUrl(event.url())?.let { segmentID ->
                            metric.segmentID(segmentID)
                            metric.segmentMissing(counter(listOf(segmentID)))
                            metric.quality(currentQuality)
                        }
                        (event to scope.async {
                            metric.downloadingIncrement()
                            metric.totalIncrement()
                            val ms = System.currentTimeMillis()
                            val result = runCatching {
                                synchronized(runningUrl) {
                                    runningUrl[event.url()] = UrlInfo(ClientType.DIRECT, System.currentTimeMillis())
                                }
                                tryDownload(event).await()?.also {
                                    metric.successDirectIncrement()
                                } ?: run {
                                    synchronized(runningUrl) {
                                        runningUrl[event.url()] = UrlInfo(ClientType.PROXY, System.currentTimeMillis())
                                    }
                                    withRetry(2) {
                                        ClientManager.getProxiedClient(room.name).get(
                                            event.url()
                                        ).readBytes().also {
                                            metric.successProxiedIncrement()
                                        }
                                    }
                                }
                            }
                            metric.updateLatency(System.currentTimeMillis() - ms)
                            synchronized(runningUrl) {
                                runningUrl.remove(event.url())
                            }
                            result.onFailure {
                                if (event is Event.LiveSegmentInit) {
                                    logger.error("failed to download init segment {}, download stopped", event.url())
                                    throw InitSegmentDownloadFiledException(it)
                                } else {
                                    val created = (event.url().substringBeforeLast("_").substringAfterLast("_")
                                        .toLongOrDefault(0))
                                    val diff = System.currentTimeMillis() / 1000 - created
                                    logger.warn(
                                        "Download segment:{} failed({}), delayed: {}s",
                                        index,
                                        (it as? ClientRequestException)?.response?.status?.value ?: it.message,
                                        diff / 1000
                                    )
                                }
                            }
                        })
                    }
                    .buffer(Channel.UNLIMITED)
                    .collect { result ->
                        logger.trace(
                            "[${room.name}] Collected: {}: {}",
                            result.first::class.simpleName,
                            result.first.url()
                        )
                        val writer = writerReference.get()
                        if (writer == null) return@collect
                        when (result.first) {
                            is Event.WSEvent -> {
                                writer.appendEvent((result.first as Event.WSEvent).data)
                            }

                            is Event.CmdFinish -> {
                                try {
                                    val file = writer.done()
                                    requireNotNull(file)
                                    scope.launch(NonCancellable) {
                                        runCatching {
                                            PostProcessor.process(
                                                file.first,
                                                ProcessorCtx(room, file.second, Date(), file.third, currentQuality)
                                            )
                                        }.onFailure {
                                            logger.error("[{}] Postprocess failed", room.name, it)
                                        }
                                    }
                                } catch (e: Exception) {
                                    logger.error("[${room.name}] Failed to postprocess", e)
                                } finally {
                                    writer.init()
                                    metric.reset()
                                }
                            }

                            else -> {
                                val bytes = result.second.await()
                                metric.downloadingDecrement()
                                metric.doneIncrement()
                                if (bytes.isSuccess) {
                                    val data = bytes.getOrThrow()
                                    if (result.first is Event.LiveSegmentInit)
                                        lastInitSegment = data
                                    metric.bytesWriteIncrement(data.size.toLong())
                                    writerReference.get()?.let { writer ->
                                        val total = writer.append(data)
                                        if (room.sizeLimit != 0L && total >= room.sizeLimit) {
                                            try {
                                                val file = writer.done()
                                                requireNotNull(file)
                                                scope.launch(NonCancellable) {
                                                    runCatching {
                                                        PostProcessor.process(
                                                            file.first,
                                                            ProcessorCtx(
                                                                room,
                                                                file.second,
                                                                Date(),
                                                                file.third,
                                                                currentQuality
                                                            )
                                                        )
                                                    }.onFailure {
                                                        logger.error("[{}] Postprocess failed", room.name, it)
                                                    }
                                                }

                                            } catch (e: Exception) {
                                                logger.error("[${room.name}] Failed to postprocess", e)
                                            } finally {
                                                writer.init()
                                                writer.append(lastInitSegment)
                                                metric.reset()
                                            }

                                        }
                                    }
                                } else {
                                    metric.failedIncrement()
                                }
                            }
                        }
                    }
            }
            generatorJob?.join()
            if (metric.data != null && metric.data!!.successDirect + metric.data!!.successProxied <= 1) {
                logger.info(
                    "[{}] No valid segments downloaded({}/{}) since start. clean empty file",
                    room.name,
                    metric.data!!.successDirect + metric.data!!.successProxied,
                    metric.data?.total
                )
                // some model start and stop their frequently
                // this cause the stream url become invalid immediately
                // so we need to reset writer to avoid empty files
                writerReference.getAndSet(null)?.dispose()
            }
        } finally {
            modelToken = null
            logger.info("[-] stop recording {}({}) q:{}(want {})", room.name, room.id, currentQuality, room.quality)
            Metric.removeMetric(room.id)
            _isActive.set(false)
        }
    }

    suspend fun join() {
        generatorJob?.join()
    }

    suspend fun stop() {
        if (_isActive.compareAndSet(true, false)) {
            logger.info("Cancelling generator job.")
            generatorJob?.cancelAndJoin()
        }
        val file = writerReference.getAndSet(null)?.done()
        if (file == null) {
            return
        }
        runCatching {
            PostProcessor.process(
                file.first,
                ProcessorCtx(room, file.second, Date(), file.third, currentQuality)
            )
        }.onFailure {
            logger.error("[{}] Postprocess failed", room.name, it)
        }
    }

    private var modelToken: String? = null
    private val streamUrl: String
        get() = buildUrl {
            val token = modelToken
            protocol = URLProtocol.HTTPS
            host = "media-hls.doppiocdn.org"
            encodedPath =
                if (currentQuality != "raw" && currentQuality.isNotBlank()) "b-hls-%d/%d/%d_%s.m3u8".format(
                    28, room.id, room.id, currentQuality
                ) else "b-hls-%d/%d/%d.m3u8".format(
                    28, room.id, room.id
                )
            if (token != null) {
                parameters["aclAuth"] = token
            }
        }.toString()

    private val eventFinish = LinkedBlockingQueue<Event>()
    fun cmdFinish() {
        eventFinish.add(Event.CmdFinish())
    }

    private fun segmentGenerator(): Flow<Event> = channelFlow {
        val scope = CoroutineScope(currentCoroutineContext())
        var initSent = false
        var initUrl = ""
        val cache = CircleCache(100)
        var retry = 0
        var ms = System.currentTimeMillis()
        var startTime = ms
        val eventFlow = EventDispatcher.subscribe(room.id)
        scope.launch {
            eventFlow.collect {
                send(Event.WSEvent(it))
            }
        }
        while (currentCoroutineContext().isActive && !shouldStop) {
            retry++
            try {
                val lines = run {
                    val rawList = (ClientManager.getProxiedClient(room.name)).get(
                        streamUrl
                    ) {
//                        parameter("psch", "v1")
//                        parameter("pkey", AUTH_KEY)
                        parameter("psch", "v2")
                        parameter("pkey", AUTH_KEY_V2)
                        parameter("preferredVideoCodec", "H265")
                    }.bodyAsText().lines()
                    if (logger.isTraceEnabled) {
                        File("${room.name}.m3u8").writeText(rawList.joinToString("\n"))
                    }
                    val newList = mutableListOf<String>()
                    for (idx in rawList.indices) {
                        if (rawList[idx].startsWith("#EXT-X-MOUFLON:URI:")) {
                            val mouflon = rawList[idx].substringAfterLast("#EXT-X-MOUFLON:URI:")
                            val encrypted =
                                mouflon.replace("(_part\\d)?\\.mp4".toRegex(), "")
                                    .substringBeforeLast("_")
                                    .substringAfterLast("_")

                            val decrypted = try {
                                val result = runCatching {
                                    Decrypter.decode(
                                        encrypted.reversed(),
                                        DECRYPT_KEY_V2
                                    )
                                }
                                result.getOrThrow()
                            } catch (e: Exception) {
                                logger.error(
                                    "[ERROR] failed to decrypt $mouflon(${encrypted.reversed()}),rawm3u8=$rawList",
                                    e
                                )
                                throw e
                            }
                            val dec = rawList[idx + 1].replace(
                                """https://media-hls\.doppiocdn\.\w+/b-hls-\d+/media.mp4""".toRegex(),
                                mouflon.replace(encrypted, decrypted)
                            )
                            newList.add(dec)
                        } else {
                            newList.add(rawList[idx])
                        }
                    }

                    newList.filterNot { it.contains("media.mp4") }
                }

                if (logger.isTraceEnabled) {
                    File("${room.name}.decoded.m3u8").writeText(lines.joinToString("\n"))
                }
                metric.updateRefreshLatency(System.currentTimeMillis() - ms)
                ms = System.currentTimeMillis()
                val initUrlCur = parseInitUrl(lines)
                if (initUrl.isNotBlank() && initUrlCur != initUrl) {
                    logger.warn("[${room.name}] Init segment changed, splitting file...")
                    send(Event.CmdFinish())
                    // reset state
                    initSent = false
                    initUrl = ""
                    startTime = System.currentTimeMillis()
                    continue
                }
                initUrl = initUrlCur
                val segmentUrls = parseSegmentUrl(lines)
                if (segmentUrls.isEmpty()) {
                    logger.warn("[{}] Got 0 videos from playlist, maybe decode failed!", room.name)
                }

                if (!initSent) {
                    initSent = true
                    send(Event.LiveSegmentInit(initUrl))
                }
                for (url in segmentUrls) {
                    // record time limit
                    if (System.currentTimeMillis() - startTime > room.timeLimit.inWholeMilliseconds && !cache.contains(
                            url
                        )
                    ) {
                        send(Event.CmdFinish())
                        // reset state
                        initSent = false
                        initUrl = ""
                        startTime = System.currentTimeMillis()
                        break
                    }
                    // external finish cmd
                    val poll = eventFinish.poll()
                    if (poll != null) {
                        send(poll)
                        // reset state
                        initSent = false
                        initUrl = ""
                        startTime = System.currentTimeMillis()
                        break
                    }
                    // normal segments
                    if (!cache.contains(url)) {
                        cache.add(url)
                        send(Event.LiveSegmentData(url))
                    }
                }
                retry = 0
            } catch (_: TimeoutCancellationException) {
                logger.warn("[${room.name}] Refresh list timeout {}, trys={}", streamUrl, retry)
                if (!runCatching { testAndConfigure() }.getOrElse { false }) {
                    logger.info("[STOP] [{}] Room off or non-public", room.name)
                    break
                }
                continue
            } catch (e: ClientRequestException) {
                if (e.response.status.value == 404) {
                    logger.info(
                        "[STOP] [{}] Stream url returns 404, this is caused by model's network connection issue",
                        room.name
                    )
                    break
                }
                if (e.response.status.value == 403) {
                    if (!runCatching { testAndConfigure() }.getOrElse { false }) {
                        logger.info("[STOP] [{}] Room off or non-public (403)", room.name)
                        break
                    } else if (currentQuality == "raw") {
                        // https://github.com/RikaCelery/XhRec/issues/2
                        logger.warn(
                            "[{}] Unable to use 'raw' quality, try using the highest one. Room will stop record now",
                            room.name
                        )
                        room.quality = "2560p60" // try selecting the highest quality
                        break
                    } else {
                        logger.error(
                            "[STOP] [{}] Refresh list error {}. Stop recording",
                            room.name,
                            e.response.status.value
                        )
                        break
                    }
                }
            } catch (e: CombinedException) {
                if (e.exceptions.any(shouldStop())) {
                    scope.launch { stop() }
                    break
                } else {
                    logger.error("[{}] Generator error", room.name, e)
                }
            } catch (_: CancellationException) {
                break
            } catch (e: Exception) {
                logger.error("[{}] Unexpected error in segment generator", room.name, e)
                if (!runCatching { testAndConfigure() }.getOrElse { false }) {
                    logger.error("[STOP] [{}] Room off or non-public:", room.name)
                    break
                }
            }

            delay(500)
        }
        logger.debug("[${room.name}] Segment generator exited.")
        EventDispatcher.unsubscribe(room.id)
        close()
    }

    private fun parseSegmentUrl(lines: List<String>): List<String> {
        val lowLatency = lines.filter { it.startsWith("#EXT-X-PART") && it.contains("URI=\"") }.mapNotNull { line ->
            try {
                line.substringAfter("URI=\"").substringBefore("\"")
            } catch (e: Exception) {
                logger.error("Failed to parse segment URL from line: $line", e)
                null
            }
        }
        return lowLatency.ifEmpty {
            lines.filter { it.startsWith("https://") && it.endsWith(".mp4") }
        }
    }

    private fun parseInitUrl(lines: List<String>): String {
        return try {
            lines.first { it.startsWith("#EXT-X-MAP") }.substringAfter("#EXT-X-MAP:URI=").removeSurrounding("\"")
        } catch (e: NoSuchElementException) {
            throw IllegalArgumentException("Missing #EXT-X-MAP tag in playlist", e)
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to parse init URL", e)
        }
    }

    private val regexCache = """media-hls\.doppiocdn\.\w+/(b-hls-\d+)""".toRegex()

    private fun tryDownload(event: Event): Deferred<ByteArray?> = scope.async {
        val c = when (event) {
            is Event.LiveSegmentData -> ClientManager.getClient(room.name)
            is Event.LiveSegmentInit -> ClientManager.getProxiedClient(room.name)
            else -> throw IllegalStateException("unknown Event type $event")
        }
        val created =
            (event.url().replace("(_part\\d)?.mp4".toRegex(), "").substringAfterLast("_").substringAfterLast("_")
                .toLongOrDefault(0))
        val diff = System.currentTimeMillis() / 1000 - created
        val wait = (20L - diff) * 1000
        withTimeoutOrNull(if (wait > 0) wait else 0) {
            withRetry(3) { attempt ->
                try {
                    c.get(event.url()).readRawBytes()
                } catch (_: TimeoutCancellationException) {
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
//                    println("Download attempt $attempt failed: ${e.message}")
                    throw e
                }
            }
        }
    }

    class InitSegmentDownloadFiledException(cause: Throwable) : Throwable(cause)

    private fun shouldStop(): (Throwable) -> Boolean = { error ->
        error is ClientRequestException && error.response.status == HttpStatusCode.NotFound || error is ClientRequestException && error.response.status == HttpStatusCode.Forbidden || error is CancellationException || !isActive
    }
}
