package github.rikacelery.v3.components

import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.DataChannel
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.OrderedEmitter
import github.rikacelery.v3.data.DownloadMeta
import github.rikacelery.v3.data.DownloadResult
import github.rikacelery.v3.events.*
import github.rikacelery.v3.hooks.DownloaderHook
import github.rikacelery.v3.utils.CdnSelector
import github.rikacelery.v3.utils.ClientManager
import io.ktor.client.*
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

sealed interface DownloaderMsg
data class DoDownload(val cmd: Download) : DownloaderMsg
data class DoCutPoint(val cut: CutPoint) : DownloaderMsg

data class ActiveDownload(
    val emitter: OrderedEmitter,
    val semaphore: Semaphore,
    val runningJobs: MutableSet<Job> = ConcurrentHashMap.newKeySet(),
    var idx: AtomicInteger = AtomicInteger(-1),
    @Volatile var generation: Long = 0L,
    @Volatile var active: Boolean = true
)

class DownloaderComponent(
    private val dataChannel: DataChannel,
    private val hooks: List<DownloaderHook> = emptyList(),
    eventBus: EventBus,
    parentScope: CoroutineScope,
    private val initialConcurrency: Int = 16
) : Actor<DownloaderMsg>("DownloaderComponent", eventBus, parentScope) {

    private val rooms = ConcurrentHashMap<Long, ActiveDownload>()
    private val workerScope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob() + CoroutineName("downloader-worker")
    )

    override suspend fun handle(msg: DownloaderMsg) {
        if (!scope.isActive) return
        when (msg) {
            is DoDownload -> handleDownload(msg.cmd)
            is DoCutPoint -> handleCutPoint(msg.cut)

        }
    }

    private suspend fun handleDownload(cmd: Download) {
        val active = rooms.getOrPut(cmd.roomId) {
            ActiveDownload(
                emitter = OrderedEmitter(cmd.roomId) { dataChannel.send(it) },
                semaphore = Semaphore(initialConcurrency)
            )
        }
        if (!active.active) return
        active.generation = cmd.generation
        val gen = cmd.generation   // capture the batch generation so workers never read a newer one at completion

        // Fire probe round on the first segment of each batch, then throttle by time.
        val probeBaseUrl = cmd.urls.firstOrNull()?.url ?: ""
        if (probeBaseUrl.isNotEmpty() && System.currentTimeMillis() - probeLastAt >= probeIntervalMs
            && probeInFlight.compareAndSet(false, true)) {
            probeCounter.incrementAndGet()
            val currentHost = CdnSelector.hostOf(probeBaseUrl)
            val probeTargets = CdnSelector.hosts.filter { it != currentHost && it.isNotEmpty() }
            if (probeTargets.isNotEmpty()) {
                val probeUrl = probeBaseUrl
                workerScope.launch {
                    try {
                        probeHosts(probeTargets, probeUrl)
                    } finally {
                        probeInFlight.set(false)
                        probeLastAt = System.currentTimeMillis()
                    }
                }
            } else {
                probeInFlight.set(false)
                probeLastAt = System.currentTimeMillis()
            }
        }

        for (seg in cmd.urls) {
            val idx = active.idx.incrementAndGet()
            var url = seg.url
            hooks.forEach { url = it.beforeDownload(url) }

            val job = workerScope.launch {
                try {
                    active.semaphore.withPermit {
                        eventBus.publish(DownloadStarted(cmd.roomId, idx, url, System.currentTimeMillis()))
                        val result = downloadSegment(url, idx)
                        val hooked = hooks.fold(result) { acc, hook -> hook.onDownloadResult(cmd.roomId, acc) }
                        active.emitter.complete(idx.toLong(), hooked)

                        when (result) {
                            is DownloadResult.Success -> {
                                eventBus.publish(SegmentDownloaded(cmd.roomId, idx, seg.url,
                                    result.meta.fetchDurationMs, result.meta.proxied, result.data.size, gen))
                            }
                            is DownloadResult.Failed -> {
                                eventBus.publish(DownloadError(cmd.roomId, idx, seg.url, result.reason))
                            }
                            is DownloadResult.CutPoint -> {}
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Still account for the segment so OrderedEmitter cannot stall forever.
                    withContext(NonCancellable) {
                        active.emitter.complete(idx.toLong(), DownloadResult.Failed(idx, seg.url, "cancelled", transportError = true))
                    }
                    throw e
                } catch (e: Exception) {
                    logger.error("Download worker failed: idx=$idx, url=${seg.url}", e)
                    withContext(NonCancellable) {
                        active.emitter.complete(idx.toLong(), DownloadResult.Failed(idx, seg.url, e.message ?: "worker error", transportError = true))
                    }
                }
            }
            active.runningJobs.add(job)
            job.invokeOnCompletion { active.runningJobs.remove(job) }
        }
    }

    private suspend fun handleCutPoint(cut: CutPoint) {
        val active = rooms[cut.roomId] ?: return
        val idx = active.idx.incrementAndGet().toLong()
        logger.info("CutPoint roomId={}, index={}, reason={}", cut.roomId, cut.index, cut.reason)
        // once complete returns, StreamEnd is in the DataChannel FIFO; Session restart is safe only after that
        active.emitter.complete(idx, DownloadResult.CutPoint(cut))
        eventBus.publish(CutPointDone(cut.roomId, cut.generation, cut.reason))
    }

    /** Start a parallel proxied attempt if the direct attempt hasn't succeeded within this time. */
    private val raceThresholdMs: Long = 8_000
    /** Hard cap for a single (host × direct/proxy) attempt. */
    private val perAttemptTimeoutMs: Long = 25_000
    /**
     * Overall budget to obtain a segment before giving up ("尽力获取" window).
     * A 404 (assignment expired) stops the loop immediately; transport errors / stalls /
     * 5xx keep cycling through the remaining CDN hosts until the deadline.
     */
    private val segmentDeadlineMs: Long = 120_000
    /** Stall watchdog: abort the attempt when no bytes arrive within this window. */
    private val stallTimeoutMs: Long = 5_000
    /** Base backoff between cross-host retries (jittered). */
    private val retryBaseBackoffMs: Long = 500

    /** Thrown when a download stalls (no bytes for [stallTimeoutMs]); treated as transport error. */
    private class StreamStallException(message: String) : Exception(message)

    // ── Probe scheduling ──
    /** Probe interval: fire probes for non-selected hosts this often (ms). */
    private val probeIntervalMs: Long = 15_000
    /** Counter of segments since last probe round (per downloader). */
    private val probeCounter = java.util.concurrent.atomic.AtomicInteger(0)
    /** Allow one probe round at a time to avoid probe storms. */
    private val probeInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Last time a probe round was fired. */
    @Volatile private var probeLastAt: Long = 0L

    /**
     * Fetch one segment, trying as hard as possible before the assignment expires:
     *  - 1st attempt goes through [CdnSelector.select] (balanced across near-best CDNs);
     *    retries walk the ranked host list, skipping hosts already tried for THIS segment.
     *  - each attempt races direct vs proxied on the chosen host;
     *  - a stall (no bytes for [stallTimeoutMs]) aborts the attempt and moves on;
     *  - 404 means the assignment expired server-side → stop immediately, no retry;
     *  - other 4xx are likewise permanent (auth/URL problem), so they also stop the loop;
     *  - transport errors / stalls / 5xx penalize the host and retry until [segmentDeadlineMs].
     */
    private suspend fun downloadSegment(url: String, idx: Int): DownloadResult {
        val start = System.currentTimeMillis()
        val deadline = start + segmentDeadlineMs
        val tried = LinkedHashSet<String>()
        var lastFail: DownloadResult.Failed? = null
        var attempts = 0

        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break

            val host = selectAttemptHost(url, tried)
            if (host.isEmpty()) break
            tried += host
            attempts++

            val resolvedUrl = if (CdnSelector.hosts.isNotEmpty()) CdnSelector.rewriteHost(url, host) else url
            val result = try {
                attemptDownload(resolvedUrl, idx, minOf(perAttemptTimeoutMs, remaining))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("downloadSegment attempt failed: idx=$idx, host=$host", e)
                DownloadResult.Failed(idx, resolvedUrl, e.message ?: "download failed", transportError = true)
            }

            when {
                result is DownloadResult.Success -> {
                    CdnSelector.record(host, result.meta.fetchDurationMs)
                    return result
                }
                result is DownloadResult.Failed && result.statusCode == 404 -> {
                    // Assignment expired server-side — retrying any host is pointless.
                    logger.info("Segment #{} gone (404 on {}), giving up after {} attempt(s)", idx, host, attempts)
                    return result
                }
                result is DownloadResult.Failed && (result.statusCode ?: 0) in 400..499 -> {
                    // Other client errors (403 etc.): the URL itself is rejected everywhere.
                    logger.info("Segment #{} rejected (HTTP {} on {}), giving up", idx, result.statusCode, host)
                    return result
                }
                result is DownloadResult.Failed -> {
                    if (result.transportError) CdnSelector.recordFailure(host)
                    logger.debug("Segment #{} attempt {} failed on {}: {}", idx, attempts, host, result.reason)
                    lastFail = result
                }
                else -> { /* CutPoint cannot happen here */ }
            }

            val backoff = retryBaseBackoffMs + Random.nextLong(0, retryBaseBackoffMs)
            if (System.currentTimeMillis() + backoff >= deadline) break
            delay(backoff)
        }

        return lastFail?.copy(
            reason = lastFail.reason + " (gave up after $attempts attempt(s)/${System.currentTimeMillis() - start}ms)"
        ) ?: DownloadResult.Failed(idx, url, "segment deadline ${segmentDeadlineMs}ms exceeded", transportError = true)
    }

    /**
     * Host for the next attempt: the balanced selector for the first one (so normal traffic
     * is load-distributed), then the best-ranked hosts not yet tried for this segment.
     * When every host has been tried (or is cooling down), re-rank all hosts — cooldowns
     * are short and the assignment is about to expire, so a second pass is worth it.
     */
    private fun selectAttemptHost(url: String, tried: Set<String>): String {
        if (CdnSelector.hosts.isEmpty()) return CdnSelector.hostOf(url)
        if (tried.isEmpty()) return CdnSelector.select()
        val now = System.currentTimeMillis()
        return CdnSelector.rankedHosts(now, exclude = tried).firstOrNull()
            ?: CdnSelector.rankedHosts(now, includeCooling = true).firstOrNull()
            ?: CdnSelector.select(now)
    }

    /**
     * One (host × direct/proxy-race) attempt, capped by [budgetMs]. Child coroutines are
     * structured (coroutineScope), so a timeout or a lost race cancels the loser instead
     * of leaking it until the socket timeout.
     */
    private suspend fun attemptDownload(resolvedUrl: String, idx: Int, budgetMs: Long): DownloadResult {
        val start = System.currentTimeMillis()
        return withTimeoutOrNull(budgetMs.milliseconds) {
            coroutineScope {
                val directDeferred = async {
                    downloadWithClient(ClientManager.getClient("dl_${Random.nextInt(32)}"), resolvedUrl, idx, false)
                }

                val directResult = withTimeoutOrNull(minOf(raceThresholdMs, budgetMs).milliseconds) { directDeferred.await() }
                if (directResult is DownloadResult.Success) {
                    val dur = System.currentTimeMillis() - start
                    return@coroutineScope directResult.copy(meta = directResult.meta.copy(fetchDurationMs = dur, proxied = false))
                }
                // A 4xx (404 = expired assignment) is permanent — stop right away instead of racing the proxy.
                if (directResult is DownloadResult.Failed && (directResult.statusCode ?: 0) in 400..499) {
                    return@coroutineScope directResult
                }

                logger.debug("Direct download slow/failed for idx={}, falling back to proxy race", idx)
                val proxyDeferred = async {
                    downloadWithClient(ClientManager.getProxiedClient("px_${Random.nextInt(5)}"), resolvedUrl, idx, true)
                }

                val result = if (directDeferred.isCompleted) {
                    // Direct already finished with a non-success result. Give the proxy a real
                    // chance instead of letting select() immediately return the direct failure.
                    withTimeoutOrNull(minOf(raceThresholdMs, budgetMs).milliseconds) { proxyDeferred.await() }
                        ?: DownloadResult.Failed(idx, resolvedUrl, "proxy timeout", transportError = true)
                } else {
                    select<DownloadResult> {
                        directDeferred.onAwait { r ->
                            (r as? DownloadResult.Success)?.copy(meta = r.meta.copy(
                                fetchDurationMs = System.currentTimeMillis() - start, proxied = false)) ?: r
                        }
                        proxyDeferred.onAwait { r ->
                            (r as? DownloadResult.Success)?.copy(meta = r.meta.copy(
                                fetchDurationMs = System.currentTimeMillis() - start, proxied = true)) ?: r
                        }
                    }
                }

                if (!directDeferred.isCompleted) directDeferred.cancel()
                if (!proxyDeferred.isCompleted) proxyDeferred.cancel()
                result
            }
        } ?: DownloadResult.Failed(idx, resolvedUrl, "attempt timeout after ${budgetMs}ms", transportError = true)
    }

    private suspend fun downloadWithClient(
        client: HttpClient, url: String, idx: Int, proxied: Boolean
    ): DownloadResult {
        return try {
            // Stall watchdog #1: no response headers within stallTimeoutMs → dead path, bail out.
            val response = withTimeout(stallTimeoutMs.milliseconds) { client.get(url) }
            val stream = response.bodyAsChannel()
            val bos = ByteArrayOutputStream()
            while (!stream.isClosedForRead) {
                val buf = ByteArray(8192)
                // Stall watchdog #2: no bytes within stallTimeoutMs → disconnect and retry elsewhere.
                val read = try {
                    withTimeout(stallTimeoutMs.milliseconds) { stream.readAvailable(buf) }
                } catch (e: TimeoutCancellationException) {
                    // rethrow if the race cancelled us; otherwise it's a genuine stall
                    currentCoroutineContext().ensureActive()
                    throw StreamStallException("no data for ${stallTimeoutMs}ms")
                }
                if (read <= 0) break
                bos.write(buf, 0, read)
            }
            DownloadResult.Success(bos.toByteArray(), DownloadMeta(url, 0, proxied, Instant.now()))
        } catch (e: TimeoutCancellationException) {
            // our own stall watchdog on client.get(); external cancellation is rethrown via ensureActive
            currentCoroutineContext().ensureActive()
            logger.warn("downloadWithClient stalled: idx=$idx, url=$url, proxied=$proxied (no response within ${stallTimeoutMs}ms)")
            DownloadResult.Failed(idx, url, "stall: no response within ${stallTimeoutMs}ms", transportError = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // the download race was resolved and this coroutine was cancelled — not an error
            throw e
        } catch (e: StreamStallException) {
            logger.warn("downloadWithClient stalled: idx=$idx, url=$url, proxied=$proxied (${e.message})")
            DownloadResult.Failed(idx, url, "stall: " + e.message, transportError = true)
        } catch (e: ResponseException) {
            // HTTP status errors (404 etc.) are routine — one line, no stack trace.
            // 4xx = the assignment itself is rejected (host is fine); 5xx implicates the host.
            logger.warn("downloadWithClient failed: idx=$idx, url=$url, proxied=$proxied, status=${e.response.status}")
            DownloadResult.Failed(
                idx, url, "HTTP " + e.response.status,
                transportError = e !is ClientRequestException,
                statusCode = e.response.status.value
            )
        } catch (e: Exception) {
            logger.error("downloadWithClient failed: idx=$idx, url=$url, proxied=$proxied", e)
            DownloadResult.Failed(idx, url, e.message ?: "download failed", transportError = true)
        }
    }

    /**
     * Probe non-selected CDN hosts with a lightweight HEAD request, recording results
     * into CdnSelector's independent probe stats. Runs concurrently with main downloads;
     * probe failures (404/timeout) do NOT cool down the host immediately.
     */
    private suspend fun probeHosts(hosts: List<String>, url: String) {
        for (host in hosts) {
            try {
                val rewritten = CdnSelector.rewriteHost(url, host)
                val probeStart = System.currentTimeMillis()
                val client = ClientManager.getClient("probe_" + Random.nextInt(32))
                try {
                    withTimeoutOrNull(5_000) {
                        client.head(rewritten)
                    }
                    CdnSelector.probe(host, System.currentTimeMillis() - probeStart)
                } catch (e: ResponseException) {
                    // 404 etc. during probe: record as probe failure, do NOT cooldown
                    CdnSelector.probeFailure(host)
                } catch (e: Exception) {
                    CdnSelector.probeFailure(host)
                }
            } catch (e: Exception) {
                logger.debug("Probe failed for host={}", host)
            }
        }
    }
}