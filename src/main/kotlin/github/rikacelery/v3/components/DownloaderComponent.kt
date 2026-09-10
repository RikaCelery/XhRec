package github.rikacelery.v3.components

import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.DataChannel
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.OrderedEmitter
import github.rikacelery.v3.data.DownloadMeta
import github.rikacelery.v3.data.DownloadResult
import github.rikacelery.v3.data.RuntimeTuning
import github.rikacelery.v3.events.*
import github.rikacelery.v3.hooks.DownloaderHook
import github.rikacelery.v3.utils.CdnSelector
import github.rikacelery.v3.utils.DefaultHttpClientProvider
import github.rikacelery.v3.utils.HttpClientProvider
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
    private val initialConcurrency: Int = 16,
    private val httpClientProvider: HttpClientProvider = DefaultHttpClientProvider,
    private val runtimeTuning: RuntimeTuning = RuntimeTuning()
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
            val job = workerScope.launch {
                val history = FailureHistory()
                var failureLogged = false
                fun logFailure(reason: String) {
                    if (failureLogged) return
                    failureLogged = true
                    logger.warn("Segment download failed: roomId={}, idx={}, url={}, reason={}, {}",
                        cmd.roomId, idx, seg.url, reason, history.summary())
                }
                try {
                    active.semaphore.withPermit {
                        eventBus.publish(DownloadStarted(cmd.roomId, idx, seg.url, System.currentTimeMillis()))
                        var url = seg.url
                        hooks.forEach { url = it.beforeDownload(url) }
                        val result = downloadSegment(url, idx, history)
                        val hooked = hooks.fold(result) { acc, hook -> hook.onDownloadResult(cmd.roomId, acc) }
                        active.emitter.complete(idx.toLong(), hooked)

                        when (hooked) {
                            is DownloadResult.Success -> {
                                eventBus.publish(SegmentDownloaded(cmd.roomId, idx, seg.url,
                                    hooked.meta.fetchDurationMs, hooked.meta.proxied, hooked.data.size, gen))
                            }
                            is DownloadResult.Failed -> {
                                logFailure(hooked.reason)
                                eventBus.publish(DownloadError(cmd.roomId, idx, seg.url, hooked.reason))
                            }
                            is DownloadResult.CutPoint -> {}
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Still account for the segment so OrderedEmitter cannot stall forever.
                    withContext(NonCancellable) {
                        try {
                            active.emitter.complete(idx.toLong(), DownloadResult.Failed(idx, seg.url, "cancelled", transportError = true))
                        } finally {
                            logFailure("cancelled")
                            eventBus.publish(DownloadError(cmd.roomId, idx, seg.url, "cancelled"))
                        }
                    }
                    throw e
                } catch (e: Exception) {
                    logger.trace("Download worker failed: idx=$idx, url=${seg.url}", e)
                    withContext(NonCancellable) {
                        val reason = e.message ?: "worker error"
                        try {
                            active.emitter.complete(idx.toLong(), DownloadResult.Failed(idx, seg.url, reason, transportError = true))
                        } finally {
                            logFailure(reason)
                            eventBus.publish(DownloadError(cmd.roomId, idx, seg.url, reason))
                        }
                    }
                }
            }
            active.runningJobs.add(job)
            job.invokeOnCompletion { active.runningJobs.remove(job) }
        }
    }

    private suspend fun handleCutPoint(cut: CutPoint) {
        // A cut can arrive before the session downloaded anything (a stop right after the
        // recording started, a room that went offline immediately). Dropping it would leave the
        // file open, the session stuck in Closing and the scheduler in Stopping forever, so the
        // room state is created on demand — exactly like the first download does.
        val active = rooms.getOrPut(cut.roomId) {
            ActiveDownload(
                emitter = OrderedEmitter(cut.roomId) { dataChannel.send(it) },
                semaphore = Semaphore(initialConcurrency)
            )
        }
        val idx = active.idx.incrementAndGet().toLong()
        logger.info("CutPoint roomId={}, index={}, reason={}", cut.roomId, cut.index, cut.reason)
        // once this returns, StreamEnd is in the DataChannel FIFO; Session restart is safe only after that
        active.emitter.completeAndAwait(idx, DownloadResult.CutPoint(cut))
        eventBus.publish(CutPointDone(cut.roomId, cut.generation, cut.reason))
    }

    /** Thrown when a download stalls; treated as transport error. */
    private class StreamStallException(message: String) : Exception(message)

    /** Shared by a segment's direct/proxy requests; repeated failures are counted together. */
    private class FailureHistory {
        var attempts = 0
        private var stalls = 0
        private val failures = linkedMapOf<String, Int>()

        @Synchronized
        fun record(url: String, route: String, reason: String) {
            if (reason.startsWith("stall:")) stalls++
            val key = "${CdnSelector.hostOf(url)} $route: $reason"
            failures[key] = (failures[key] ?: 0) + 1
        }

        @Synchronized
        fun summary(): String = "attempts=$attempts, stalls=$stalls, history=[" +
            failures.entries.joinToString("; ") { (reason, count) -> "$reason (x$count)" } + "]"
    }

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
    private suspend fun downloadSegment(url: String, idx: Int, history: FailureHistory): DownloadResult {
        val start = System.currentTimeMillis()
        val segmentDeadlineMs = runtimeTuning.downloaderDeadline.inWholeMilliseconds
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
            history.attempts = attempts

            val resolvedUrl = if (CdnSelector.hosts.isNotEmpty()) CdnSelector.rewriteHost(url, host) else url
            val result = try {
                attemptDownload(resolvedUrl, idx, minOf(runtimeTuning.downloaderAttemptTimeout.inWholeMilliseconds, remaining), history)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.trace("downloadSegment attempt failed: idx=$idx, host=$host", e)
                val reason = e.message ?: "download failed"
                history.record(resolvedUrl, "ATTEMPT", reason)
                DownloadResult.Failed(idx, resolvedUrl, reason, transportError = true)
            }

            when {
                result is DownloadResult.Success -> {
                    CdnSelector.record(host, result.meta.fetchDurationMs)
                    return result
                }
                result is DownloadResult.Failed && result.statusCode == 404 -> {
                    // Assignment expired server-side — retrying any host is pointless.
                    logger.trace("Segment #{} gone (404 on {}), giving up after {} attempt(s)", idx, host, attempts)
                    return result
                }
                result is DownloadResult.Failed && (result.statusCode ?: 0) in 400..499 -> {
                    // Other client errors (403 etc.): the URL itself is rejected everywhere.
                    logger.trace("Segment #{} rejected (HTTP {} on {}), giving up", idx, result.statusCode, host)
                    return result
                }
                result is DownloadResult.Failed -> {
                    if (result.transportError) CdnSelector.recordFailure(host)
                    logger.trace("Segment #{} attempt {} failed on {}: {}", idx, attempts, host, result.reason)
                    lastFail = result
                }
                else -> { /* CutPoint cannot happen here */ }
            }

            val retryBaseBackoffMs = runtimeTuning.downloaderRetryBackoff.inWholeMilliseconds
            val backoff = if (retryBaseBackoffMs > 0) {
                retryBaseBackoffMs + Random.nextLong(0, retryBaseBackoffMs)
            } else {
                0
            }
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
    private suspend fun attemptDownload(resolvedUrl: String, idx: Int, budgetMs: Long, history: FailureHistory): DownloadResult {
        val start = System.currentTimeMillis()
        return withTimeoutOrNull(budgetMs.milliseconds) {
            coroutineScope {
                val directDeferred = async {
                    downloadWithClient(httpClientProvider.direct("dl_${Random.nextInt(32)}"), resolvedUrl, idx, false, history)
                }

                val raceThresholdMs = runtimeTuning.downloaderRaceDelay.inWholeMilliseconds
                val directResult = withTimeoutOrNull(minOf(raceThresholdMs, budgetMs).milliseconds) { directDeferred.await() }
                if (directResult is DownloadResult.Success) {
                    val dur = System.currentTimeMillis() - start
                    return@coroutineScope directResult.copy(meta = directResult.meta.copy(fetchDurationMs = dur, proxied = false))
                }
                // A 4xx (404 = expired assignment) is permanent — stop right away instead of racing the proxy.
                if (directResult is DownloadResult.Failed && (directResult.statusCode ?: 0) in 400..499) {
                    return@coroutineScope directResult
                }

                logger.trace("Direct download slow/failed for idx={}, falling back to proxy race", idx)
                val proxyDeferred = async {
                    downloadWithClient(httpClientProvider.proxied("px_${Random.nextInt(5)}"), resolvedUrl, idx, true, history)
                }

                val result = if (directDeferred.isCompleted) {
                    // Direct already finished with a non-success result. Give the proxy a real
                    // chance instead of letting select() immediately return the direct failure.
                    withTimeoutOrNull(minOf(raceThresholdMs, budgetMs).milliseconds) { proxyDeferred.await() }
                        ?: DownloadResult.Failed(idx, resolvedUrl, "proxy timeout", transportError = true)
                            .also { history.record(resolvedUrl, "PROXY", it.reason) }
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
            .also { history.record(resolvedUrl, "ATTEMPT", it.reason) }
    }

    private suspend fun downloadWithClient(
        client: HttpClient, url: String, idx: Int, proxied: Boolean, history: FailureHistory
    ): DownloadResult {
        val result = try {
            // Stall watchdog #1: no response headers within stallTimeoutMs → dead path, bail out.
            val response = withTimeout(runtimeTuning.downloaderStallTimeout) { client.get(url) }
            val stream = response.bodyAsChannel()
            val bos = ByteArrayOutputStream()
            while (!stream.isClosedForRead) {
                val buf = ByteArray(8192)
                // Stall watchdog #2: no bytes within stallTimeoutMs → disconnect and retry elsewhere.
                val read = try {
                    withTimeout(runtimeTuning.downloaderStallTimeout) { stream.readAvailable(buf) }
                } catch (e: TimeoutCancellationException) {
                    // rethrow if the race cancelled us; otherwise it's a genuine stall
                    currentCoroutineContext().ensureActive()
                    throw StreamStallException("no data for ${runtimeTuning.downloaderStallTimeout.inWholeMilliseconds}ms")
                }
                if (read <= 0) break
                bos.write(buf, 0, read)
            }
            DownloadResult.Success(bos.toByteArray(), DownloadMeta(url, 0, proxied, Instant.now()))
        } catch (e: TimeoutCancellationException) {
            // our own stall watchdog on client.get(); external cancellation is rethrown via ensureActive
            currentCoroutineContext().ensureActive()
            val stallTimeoutMs = runtimeTuning.downloaderStallTimeout.inWholeMilliseconds
            logger.trace("downloadWithClient stalled: idx=$idx, url=$url, proxied=$proxied (no response within ${stallTimeoutMs}ms)")
            DownloadResult.Failed(idx, url, "stall: no response within ${stallTimeoutMs}ms", transportError = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // the download race was resolved and this coroutine was cancelled — not an error
            throw e
        } catch (e: StreamStallException) {
            logger.trace("downloadWithClient stalled: idx=$idx, url=$url, proxied=$proxied (${e.message})")
            DownloadResult.Failed(idx, url, "stall: " + e.message, transportError = true)
        } catch (e: ResponseException) {
            // HTTP status errors (404 etc.) are routine — one line, no stack trace.
            // 4xx = the assignment itself is rejected (host is fine); 5xx implicates the host.
            logger.trace("downloadWithClient failed: idx=$idx, url=$url, proxied=$proxied, status=${e.response.status}")
            DownloadResult.Failed(
                idx, url, "HTTP " + e.response.status,
                transportError = e !is ClientRequestException,
                statusCode = e.response.status.value
            )
        } catch (e: Exception) {
            logger.trace("downloadWithClient failed: idx=$idx, url=$url, proxied=$proxied", e)
            DownloadResult.Failed(idx, url, e.message ?: "download failed", transportError = true)
        }
        if (result is DownloadResult.Failed) {
            history.record(url, if (proxied) "PROXY" else "DIRECT", result.reason)
        }
        return result
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
                val client = httpClientProvider.direct("probe_" + Random.nextInt(32))
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
