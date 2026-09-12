package github.rikacelery.v3.utils

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.*
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionPool
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the connection policy of the human-paced clients: the whole point of the pool, the pings
 * and the telemetry is to keep the *warm* path warm. If a change makes a stream client dial per
 * request, or adds per-request overhead to it, this fails.
 *
 * The comparison against the pre-change profile is deliberate: the same loopback workload run on
 * the old pool/timeout configuration must not be measurably faster, otherwise the new keep-alive
 * and [HttpConnectionStats] listener are costing more than they return.
 */
class HttpConnectionProfileTest {

    private val body = "#EXTM3U\n#EXT-X-MAP:URI=\"http://127.0.0.1:1/init.mp4\""

    private class Server(body: String) {
        val connections = ConcurrentHashMap.newKeySet<String>()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            server.createContext("/live.m3u8") { exchange ->
                connections += exchange.remoteAddress.toString()
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.executor = Executors.newFixedThreadPool(4)
            server.start()
        }

        val url: String get() = "http://127.0.0.1:${server.address.port}/live.m3u8"

        fun stop() = server.stop(0)
    }

    /** The profile as it was before the connection policy change: 16 idle / 5 minutes, no pings. */
    private fun baselineClient(): HttpClient = HttpClient(OkHttp) {
        expectSuccess = true
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
        install(HttpRequestRetry) {
            retryOnExceptionIf(maxRetries = 3) { _, cause -> cause !is ResponseException }
            constantDelay(300)
        }
        engine { config { connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES)) } }
    }

    private fun timeRequests(client: HttpClient, url: String, count: Int): LongArray = runBlocking {
        // warm up: the first request always dials
        client.get(url).bodyAsText()
        LongArray(count) {
            val start = System.nanoTime()
            client.get(url).bodyAsText()
            (System.nanoTime() - start) / 1_000_000
        }
    }

    private fun percentile(samples: LongArray, p: Double): Double {
        val sorted = samples.sortedArray()
        val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx].toDouble()
    }

    @Test
    fun `stream client keeps one pooled connection and records engine telemetry`() {
        assumeTrue(System.getenv("http_proxy") == null && System.getenv("HTTP_PROXY") == null) {
            "a configured proxy would bypass the loopback server"
        }
        val server = Server(body)
        HttpConnectionStats.reset()
        val oldHosts = CdnSelector.hosts
        CdnSelector.updateHosts(emptyList())
        val key = "m3u8_profile_${System.nanoTime()}"
        val client = ClientManager.getProxiedClient(key)

        try {
            val samples = timeRequests(client, server.url, 20)
            assertEquals(20, samples.size)

            val stats = HttpConnectionStats.snapshot()
                .firstOrNull { it.role == "playlist" && it.host == "127.0.0.1" }
            assertTrue(stats != null, "the playlist client must feed connection telemetry")
            assertEquals(21L, stats.requests, "warm-up plus the measured requests")
            assertTrue(stats.reusedConnections >= 18, "keep-alive must serve nearly every request: $stats")
            assertEquals(1, server.connections.size, "exactly one pooled connection expected")
        } finally {
            ClientManager.removeClient(key)
            CdnSelector.updateHosts(oldHosts)
            server.stop()
        }
    }

    @Test
    fun `stream client is not slower than the previous pool profile`() {
        assumeTrue(System.getenv("http_proxy") == null && System.getenv("HTTP_PROXY") == null) {
            "a configured proxy would bypass the loopback server"
        }
        val server = Server(body)
        val oldHosts = CdnSelector.hosts
        CdnSelector.updateHosts(emptyList())
        val key = "m3u8_profile_ab_${System.nanoTime()}"
        val baseline = baselineClient()
        val current = ClientManager.getProxiedClient(key)
        val count = 200

        try {
            // Alternate to spread any process-wide drift (GC, scheduler) over both arms.
            val baselineWarm = timeRequests(baseline, server.url, 20)
            val currentWarm = timeRequests(current, server.url, 20)
            val baselineSamples = baselineWarm + timeRequests(baseline, server.url, count)
            val currentSamples = currentWarm + timeRequests(current, server.url, count)

            val baselineP50 = percentile(baselineSamples, 0.50)
            val currentP50 = percentile(currentSamples, 0.50)
            val baselineP95 = percentile(baselineSamples, 0.95)
            val currentP95 = percentile(currentSamples, 0.95)
            println(
                "stream profile latency (loopback, ms): baseline p50=$baselineP50 p95=$baselineP95 " +
                    "current p50=$currentP50 p95=$currentP95"
            )
            // Generous on purpose: the point is to catch a structural regression (dialing per
            // request, per-request listener work), not to police sub-millisecond jitter.
            assertTrue(
                currentP50 <= baselineP50 * 1.5 + 5.0,
                "new profile regressed: baseline p50=$baselineP50 current p50=$currentP50"
            )
        } finally {
            ClientManager.removeClient(key)
            baseline.close()
            CdnSelector.updateHosts(oldHosts)
            server.stop()
        }
    }

    @Test
    fun `bulk download clients stay free of connection telemetry`() {
        assumeTrue(System.getenv("http_proxy") == null && System.getenv("HTTP_PROXY") == null) {
            "a configured proxy would bypass the loopback server"
        }
        val server = Server(body)
        HttpConnectionStats.reset()
        val key = "dl_profile_${System.nanoTime()}"
        val client = ClientManager.getClient(key)

        try {
            runBlocking {
                repeat(5) { client.get(server.url).bodyAsText() }
            }
            assertTrue(server.connections.isNotEmpty(), "the requests must have reached the server")
            assertTrue(
                HttpConnectionStats.snapshot().none { it.role == "download" },
                "the bulk path must not carry the EventListener: $server"
            )
        } finally {
            ClientManager.removeClient(key)
            server.stop()
        }
    }

    @Test
    fun `removing a client forces the next request onto a fresh client`() {
        val key = "m3u8_evict_${System.nanoTime()}"
        val first = ClientManager.getProxiedClient(key)
        ClientManager.removeClient(key)
        val second = ClientManager.getProxiedClient(key)
        try {
            assertTrue(first !== second, "removeClient must drop the cached client")
        } finally {
            ClientManager.removeClient(key)
        }

        val directKey = "dl_evict_${System.nanoTime()}"
        val directFirst = ClientManager.getClient(directKey)
        ClientManager.removeClient(directKey)
        val directSecond = ClientManager.getClient(directKey)
        try {
            assertTrue(directFirst !== directSecond, "removeClient must cover direct clients too")
        } finally {
            ClientManager.removeClient(directKey)
        }
    }
}
