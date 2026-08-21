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
    @Volatile var generation: Int = 0,
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
                                    result.meta.fetchDurationMs, result.meta.proxied, result.data.size, active.generation))
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
        active.emitter.complete(idx,  DownloadResult.CutPoint(cut))
    }

    private val raceThresholdMs: Long = 15_000
    private val segmentTimeoutMs: Long = 60_000

    // ── Probe scheduling ──
    /** Probe interval: fire probes for non-selected hosts this often (ms). */
    private val probeIntervalMs: Long = 15_000
    /** Counter of segments since last probe round (per downloader). */
    private val probeCounter = java.util.concurrent.atomic.AtomicInteger(0)
    /** Allow one probe round at a time to avoid probe storms. */
    private val probeInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Last time a probe round was fired. */
    @Volatile private var probeLastAt: Long = 0L

    private suspend fun downloadSegment(url: String, idx: Int): DownloadResult {
        val start = System.currentTimeMillis()
        // CDN host selection: rewrite to the fastest measured host (with epsilon exploration)
        val resolvedUrl = CdnSelector.resolve(url)
        val cdnHost = CdnSelector.hostOf(resolvedUrl)

        return try {
            withTimeoutOrNull(segmentTimeoutMs.milliseconds) {
                val directDeferred = scope.async {
                    downloadWithClient(ClientManager.getClient("dl_${Random.nextInt(32)}"), resolvedUrl, idx, false)
                }

                val directResult = withTimeoutOrNull(raceThresholdMs.milliseconds) { directDeferred.await() }
                if (directResult is DownloadResult.Success) {
                    val dur = System.currentTimeMillis() - start
                    CdnSelector.record(cdnHost, dur)
                    return@withTimeoutOrNull directResult.copy(meta = directResult.meta.copy(fetchDurationMs = dur, proxied = false))
                }

                logger.debug("Direct download slow/failed for idx={}, falling back to proxy race", idx)
                val proxyDeferred = scope.async {
                    downloadWithClient(ClientManager.getProxiedClient("px_${Random.nextInt(5)}"), resolvedUrl, idx, true)
                }

                val result = if (directDeferred.isCompleted) {
                    // Direct already finished with a non-success result. Give the proxy a real
                    // chance instead of letting select() immediately return the direct failure.
                    withTimeoutOrNull(raceThresholdMs.milliseconds) { proxyDeferred.await() }
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

                (result as? DownloadResult.Success)?.let {
                    CdnSelector.record(cdnHost, System.currentTimeMillis() - start)
                }
                // connection-level failures AND slow HTTP errors (404 etc. from timeout) implicate the CDN host
                if (result is DownloadResult.Failed && result.transportError) CdnSelector.recordFailure(cdnHost)
                result
            } ?: DownloadResult.Failed(idx, resolvedUrl, "segment timeout after ${segmentTimeoutMs}ms", transportError = true).also {
                CdnSelector.recordFailure(cdnHost)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            CdnSelector.recordFailure(cdnHost)
            logger.error("downloadSegment failed: idx=$idx, url=$url", e)
            DownloadResult.Failed(idx, url, e.message ?: "download failed", transportError = true)
        }
    }

    private suspend fun downloadWithClient(
        client: HttpClient, url: String, idx: Int, proxied: Boolean
    ): DownloadResult {
        return try {
            val response = client.get(url)
            val stream = response.bodyAsChannel()
            val bos = ByteArrayOutputStream()
            while (!stream.isClosedForRead) {
                val buf = ByteArray(8192)
                val read = stream.readAvailable(buf)
                if (read <= 0) break
                bos.write(buf, 0, read)
            }
            DownloadResult.Success(bos.toByteArray(), DownloadMeta(url, 0, proxied, Instant.now()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            // the download race was resolved and this coroutine was cancelled — not an error
            throw e
        } catch (e: ResponseException) {
            // HTTP status errors (404 etc.) are routine — one line, no stack trace
            logger.warn("downloadWithClient failed: idx=$idx, url=$url, proxied=$proxied, status=${e.response.status}")
            DownloadResult.Failed(idx, url, "HTTP " + e.response.status, transportError = false)
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