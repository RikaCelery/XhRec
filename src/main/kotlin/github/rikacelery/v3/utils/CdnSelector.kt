package github.rikacelery.v3.utils

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.buildUrl
import io.ktor.http.takeFrom
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow
import kotlin.random.Random

/**
 * Speed-based CDN host selector (优选).
 *
 * Tracks exponentially-weighted moving average download speed per host and picks the
 * fastest one for the bulk of downloads. To keep measurements fresh, a small fraction
 * (ε-greedy) of selections is randomly assigned to other hosts. Failed hosts enter an
 * exponential cooldown and are skipped until they recover.
 */
object CdnSelector {
    data class HostStat(
        @Volatile var speedBps: Double = 0.0,
        @Volatile var samples: Int = 0,
        @Volatile var failures: Int = 0,
        @Volatile var cooldownUntil: Long = 0L,
        @Volatile var lastSpeedBps: Double = 0.0
    )

    private val stats = ConcurrentHashMap<String, HostStat>()

    @Volatile
    var hosts: List<String> = emptyList()

    /** Fraction of selections routed to non-fastest hosts to keep probing (0.0–1.0). */
    @Volatile
    var exploreProbability: Double = 0.1

    private const val EWMA_ALPHA = 0.3
    private const val BASE_COOLDOWN_MS = 5_000L
    private const val MAX_COOLDOWN_MS = 120_000L

    fun updateHosts(newHosts: List<String>) {
        val sanitized = newHosts.mapNotNull { it.trim().trimEnd('/').takeIf(String::isNotEmpty) }.distinct()
        // drop stats for hosts that are no longer configured
        stats.keys.retainAll(sanitized.toSet())
        hosts = sanitized
    }

    private fun availableHosts(now: Long = System.currentTimeMillis()): List<String> =
        hosts.filter { host -> (stats[host]?.cooldownUntil ?: 0L) <= now }

    /** Record a successful download: bytes / durationMs → EWMA speed (bytes/sec). */
    fun record(host: String, bytes: Long, durationMs: Long) {
        if (durationMs <= 0 || bytes <= 0) return
        val instant = bytes * 1000.0 / durationMs
        val stat = stats.computeIfAbsent(host) { HostStat() }
        synchronized(stat) {
            stat.samples++
            stat.failures = 0
            stat.cooldownUntil = 0L
            stat.lastSpeedBps = instant
            stat.speedBps = if (stat.samples <= 1) instant
            else EWMA_ALPHA * instant + (1 - EWMA_ALPHA) * stat.speedBps
        }
    }

    /** Record a failure: exponential cooldown so selection skips this host for a while. */
    fun recordFailure(host: String, now: Long = System.currentTimeMillis()) {
        val stat = stats.computeIfAbsent(host) { HostStat() }
        synchronized(stat) {
            stat.failures++
            val backoff = (BASE_COOLDOWN_MS * 2.0.pow((stat.failures - 1).coerceAtMost(10))).toLong()
                .coerceAtMost(MAX_COOLDOWN_MS)
            stat.cooldownUntil = now + backoff
        }
    }

    /** Pick a host: fastest (1-ε) or random other (ε, exploration to keep measuring). */
    fun select(now: Long = System.currentTimeMillis()): String {
        val avail = availableHosts(now)
        if (avail.isEmpty()) return hosts.firstOrNull() ?: ""
        if (avail.size == 1) return avail[0]

        val fastest = fastestAvailable(avail, now)
        if (fastest == null) {
            // nothing measured yet — spread randomly to bootstrap every host's stats
            return avail[Random.nextInt(avail.size)]
        }
        if (Random.nextDouble() < exploreProbability) {
            val others = avail.filter { it != fastest }
            if (others.isNotEmpty()) return others[Random.nextInt(others.size)]
        }
        return fastest
    }

    private fun fastestAvailable(avail: List<String>, now: Long): String? {
        var best: String? = null
        var bestSpeed = 0.0
        for (host in avail) {
            val speed = stats[host]?.speedBps ?: 0.0
            if (speed > bestSpeed) {
                bestSpeed = speed
                best = host
            }
        }
        return best
    }

    /** Rewrite [url]'s host to the currently selected CDN host. */
    fun resolve(url: String, now: Long = System.currentTimeMillis()): String {
        val host = select(now)
        if (host.isEmpty()) return url
        return rewriteHost(url, host)
    }

    /** Replace the host part of an absolute URL, keeping scheme/path/query/fragment. */
    fun rewriteHost(url: String, host: String): String {
        return try {
            val u = Url(url)
            if (u.host.isEmpty()) return url
            buildUrl {
                takeFrom(u)
                this.host = host
            }.toString()
        } catch (e: Exception) {
            url
        }
    }

    fun hostOf(url: String): String = try {
        Url(url).host
    } catch (e: Exception) {
        ""
    }

    /** Snapshot of current per-host stats (for WebUI/metrics). */
    fun snapshot(): Map<String, HostStat> = stats.entries.associate { (k, v) ->
        k to synchronized(v) { v.copy() }
    }

    fun reset() {
        stats.clear()
        hosts = emptyList()
        exploreProbability = 0.1
    }
}
