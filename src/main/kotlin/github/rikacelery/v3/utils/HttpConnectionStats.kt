package github.rikacelery.v3.utils

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/**
 * Connection-level telemetry for the human-paced HTTP clients (playlist / master / preconfig).
 *
 * This answers a question the aggregate CDN stats cannot: when a request times out, was it a
 * *fresh* connection that was slow, or a *pooled* one that had stopped answering? The two have
 * opposite remedies — the first is a network/edge problem (rotate the host), the second is a
 * connection-liveness problem (drop the connection and dial again) — and only the HTTP engine can
 * tell them apart. An [okhttp3.EventListener] therefore feeds this object, attached to those
 * clients only; the bulk segment clients keep their hot path free of instrumentation.
 *
 * Reading it:
 *  - `reused > 0` and `waitHeadersAvg` close to the per-attempt timeout → the pool handed out a
 *    dead connection (the case the eviction in [ClientManager.removeClient] exists for);
 *  - `newConnections` high and `connectMsAvg` high → connecting itself is slow (edge/DNS), so host
 *    rotation is the fix, not connection recycling;
 *  - `failures` with `phase=connect` → the dial failed before any request was written.
 *
 * The counters are cumulative and cheap (LongAdder); [MetricComponent] renders them as Prometheus
 * text and `/config/hosts` exposes the per-host averages.
 */
object HttpConnectionStats {

    /** One (role, host) pair. Roles come from the client key, see `ClientManager.roleOf`. */
    data class Snapshot(
        val role: String,
        val host: String,
        val requests: Long,
        val newConnections: Long,
        val reusedConnections: Long,
        val connectMsTotal: Long,
        val waitHeadersSamples: Long,
        val waitHeadersMsTotal: Long,
        val failures: Long,
        val timeouts: Long,
        val cancelled: Long,
        val lastFailure: String?
    ) {
        val connectMsAvg: Long get() = if (newConnections > 0) connectMsTotal / newConnections else 0
        val waitHeadersMsAvg: Long get() = if (waitHeadersSamples > 0) waitHeadersMsTotal / waitHeadersSamples else 0
    }

    private class Counters {
        val requests = LongAdder()
        val newConnections = LongAdder()
        val reusedConnections = LongAdder()
        val connectMsTotal = LongAdder()
        val waitHeadersSamples = LongAdder()
        val waitHeadersMsTotal = LongAdder()
        val failures = LongAdder()
        val timeouts = LongAdder()
        val cancelled = LongAdder()

        @Volatile
        var lastFailure: String? = null
    }

    private val counters = ConcurrentHashMap<String, Counters>()

    private fun countersFor(role: String, host: String): Counters =
        counters.computeIfAbsent("$role|${host.ifEmpty { "?" }}") { Counters() }

    fun recordRequest(role: String, host: String) {
        countersFor(role, host).requests.increment()
    }

    /** A connection was acquired: [reused] is false when it had to be dialed (and then [connectMs] is its dial time). */
    fun recordConnection(role: String, host: String, reused: Boolean, connectMs: Long) {
        val c = countersFor(role, host)
        if (reused) {
            c.reusedConnections.increment()
        } else {
            c.newConnections.increment()
            c.connectMsTotal.add(connectMs.coerceAtLeast(0))
        }
    }

    /** Time from finishing the request headers to the first response header: the phase a stalled connection stalls in. */
    fun recordWaitHeaders(role: String, host: String, waitMs: Long) {
        val c = countersFor(role, host)
        c.waitHeadersSamples.increment()
        c.waitHeadersMsTotal.add(waitMs.coerceAtLeast(0))
    }

    fun recordFailure(role: String, host: String, phase: String, reason: String?, timeout: Boolean, cancelled: Boolean) {
        val c = countersFor(role, host)
        c.failures.increment()
        if (timeout) c.timeouts.increment()
        if (cancelled) c.cancelled.increment()
        c.lastFailure = "$phase: ${reason ?: "?"}"
    }

    fun snapshot(): List<Snapshot> = counters.entries
        .map { (key, c) ->
            val split = key.split('|', limit = 2)
            Snapshot(
                role = split.getOrElse(0) { "?" },
                host = split.getOrElse(1) { "?" },
                requests = c.requests.sum(),
                newConnections = c.newConnections.sum(),
                reusedConnections = c.reusedConnections.sum(),
                connectMsTotal = c.connectMsTotal.sum(),
                waitHeadersSamples = c.waitHeadersSamples.sum(),
                waitHeadersMsTotal = c.waitHeadersMsTotal.sum(),
                failures = c.failures.sum(),
                timeouts = c.timeouts.sum(),
                cancelled = c.cancelled.sum(),
                lastFailure = c.lastFailure
            )
        }
        .sortedWith(compareBy({ it.role }, { it.host }))

    fun reset() = counters.clear()
}

/**
 * Feeds [HttpConnectionStats] from OkHttp's call lifecycle. Attached only to the human-paced
 * clients: an [EventListener] is invoked for every connection and header event, which is noise on
 * the bulk segment path and exactly what is wanted for a request every few seconds.
 *
 * Call state is keyed by [Call] because OkHttp may report connection events from a different thread
 * than the request events. A listener that throws would fail the call it observes, so the callbacks
 * only touch in-memory counters.
 */
internal class ConnectionStatsListener(private val role: String) : EventListener() {

    private class CallState {
        @Volatile
        var connectStartedNs: Long = 0L

        @Volatile
        var requestHeadersEndNs: Long = 0L

        @Volatile
        var phase: String = "connect"
    }

    private val states = ConcurrentHashMap<Call, CallState>()

    private fun host(call: Call): String = call.request().url.host

    override fun callStart(call: Call) {
        states[call] = CallState()
        HttpConnectionStats.recordRequest(role, host(call))
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        val state = states[call] ?: return
        state.connectStartedNs = System.nanoTime()
        state.phase = "connect"
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        val state = states[call] ?: return
        // connectStart only fires when the connection had to be dialed, so its absence marks reuse.
        val started = state.connectStartedNs
        val reused = started == 0L
        val connectMs = if (reused) 0L else (System.nanoTime() - started) / 1_000_000
        HttpConnectionStats.recordConnection(role, host(call), reused, connectMs)
        state.phase = "request"
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        val state = states[call] ?: return
        state.requestHeadersEndNs = System.nanoTime()
        state.phase = "response"
    }

    override fun responseHeadersStart(call: Call) {
        val state = states[call] ?: return
        val started = state.requestHeadersEndNs
        if (started > 0) {
            HttpConnectionStats.recordWaitHeaders(role, host(call), (System.nanoTime() - started) / 1_000_000)
        }
    }

    override fun callEnd(call: Call) {
        states.remove(call)
    }

    override fun callFailed(call: Call, ioe: IOException) {
        finish(
            call,
            ioe.message ?: ioe::class.simpleName ?: "failed",
            timeout = ioe is SocketTimeoutException || ioe is InterruptedIOException,
            cancelled = false
        )
    }

    override fun canceled(call: Call) {
        // OkHttp reports a cancellation both here and (for a failed call) through callFailed; the
        // first of the two wins so one attempt is never counted twice.
        finish(call, "cancelled", timeout = false, cancelled = true)
    }

    private fun finish(call: Call, reason: String, timeout: Boolean, cancelled: Boolean) {
        val state = states.remove(call) ?: return
        HttpConnectionStats.recordFailure(role, host(call), state.phase, reason, timeout, cancelled)
    }
}
