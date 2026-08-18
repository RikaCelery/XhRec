package github.rikacelery.v3.utils

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ordered host list with per-host failure cooldown (exponential backoff).
 *
 * [currentHost] always returns the first host that is not cooling down; when the
 * primary fails, [markFailure] puts it into cooldown and the next available host
 * becomes current. After the cooldown expires the host returns to rotation.
 */
class HostFailover(
    initialHosts: List<String> = emptyList(),
    private val baseCooldownMs: Long = 5_000L,
    private val maxCooldownMs: Long = 120_000L
) {
    @Volatile
    var hosts: List<String> = initialHosts
        private set

    private val failCount = ConcurrentHashMap<String, Int>()
    private val cooldownUntil = ConcurrentHashMap<String, Long>()
    private val epoch = AtomicInteger(0)

    /** Replace the host list and reset all failure state. */
    fun updateHosts(newHosts: List<String>) {
        val sanitized = newHosts.mapNotNull { it.trim().trimEnd('/').takeIf(String::isNotEmpty) }.distinct()
        epoch.incrementAndGet()
        failCount.clear()
        cooldownUntil.clear()
        hosts = sanitized
    }

    /** Hosts currently out of cooldown, in configured order. */
    fun availableHosts(now: Long = System.currentTimeMillis()): List<String> =
        hosts.filter { host -> (cooldownUntil[host] ?: 0L) <= now }

    /** First available host, or first host overall when everything is cooling down, or null. */
    fun currentHost(now: Long = System.currentTimeMillis()): String? {
        val avail = availableHosts(now)
        if (avail.isNotEmpty()) return avail.first()
        return hosts.firstOrNull()
    }

    /** Move to the next available host, marking [host] as failed (cooldown with backoff). */
    fun markFailure(host: String, now: Long = System.currentTimeMillis()) {
        val n = failCount.merge(host, 1, Int::plus) ?: 1
        val backoff = (baseCooldownMs shl (n - 1).coerceAtMost(10)).coerceAtMost(maxCooldownMs)
        cooldownUntil[host] = now + backoff
    }

    fun markSuccess(host: String) {
        failCount[host] = 0
        cooldownUntil.remove(host)
    }

    fun reset() {
        epoch.incrementAndGet()
        failCount.clear()
        cooldownUntil.clear()
    }

    fun failureCount(host: String): Int = failCount[host] ?: 0

    /** Returns true when [host] is currently cooling down. */
    fun isCoolingDown(host: String, now: Long = System.currentTimeMillis()): Boolean =
        (cooldownUntil[host] ?: 0L) > now
}
