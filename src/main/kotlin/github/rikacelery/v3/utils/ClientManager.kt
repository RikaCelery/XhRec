package github.rikacelery.v3.utils

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import okhttp3.ConnectionPool
import okhttp3.Protocol
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

object ClientManager {
    private val logger = LoggerFactory.getLogger(ClientManager::class.java)

    /**
     * The human-paced clients: a playlist, master or preconfig request every few seconds. They are
     * the ones where a pooled connection can sit idle long enough for the peer (or a proxy) to drop
     * it silently, so they get a shorter idle policy, HTTP/2 keep-alive pings and connection
     * telemetry. The bulk segment clients (`dl_`/`px_`) keep the default pool and stay untouched:
     * an [okhttp3.EventListener] and a ping timer on a path doing hundreds of requests per second
     * is pure overhead, and their own stall watchdog already abandons dead connections.
     */
    private fun isStreamKey(key: String): Boolean =
        key.startsWith("m3u8_") || key.startsWith("master_") || key.startsWith("preconfig_")

    /** Role label for [HttpConnectionStats]; keep the set small, it labels metrics. */
    private fun roleOf(key: String): String = when {
        key.startsWith("m3u8_") -> "playlist"
        key.startsWith("master_") -> "master"
        key.startsWith("preconfig_") -> "preconfig"
        key.startsWith("dl_") -> "download"
        key.startsWith("px_") -> "proxy"
        else -> "other"
    }

    /**
     * A stream client polls every few seconds, so a connection idle for more than 30s is not doing
     * useful work — but it can still be a socket the peer quietly dropped, and handing that to the
     * next poll costs a full timeout. Dropping it early costs one handshake that would have to
     * happen anyway. Bulk clients keep the default 5 minutes.
     */
    private fun newPool(stream: Boolean): ConnectionPool =
        if (stream) ConnectionPool(4, 30, TimeUnit.SECONDS)
        else ConnectionPool(16, 5, TimeUnit.MINUTES)

    /**
     * http1=true forces HTTP/1.1 — required for the stripchat.com WAF: its HTTP/2
     * fingerprint check rejects OkHttp (non-browser h2), while HTTP/1.1 + browser
     * navigation headers passes. CDN clients (doppiocdn.org) keep HTTP/2.
     */
    private fun clientDirect(key: String, http1: Boolean, expectSuccess: Boolean): HttpClient {
        val stream = isStreamKey(key)
        // Created once and captured: Ktor derives a separate OkHttpClient per request-timeout
        // profile, and they must share this pool or connection reuse would be lost per profile.
        val pool = newPool(stream)
        val listener = if (stream) ConnectionStatsListener(roleOf(key)) else null
        logger.debug("create direct client key={} http1={} expectSuccess={}", key, http1, expectSuccess)
        return HttpClient(OkHttp) {
            configureClient()
            this.expectSuccess = expectSuccess
            engine {
                config {
                    connectionPool(pool)
                    followSslRedirects(true)
                    followRedirects(true)
                    if (http1) protocols(listOf(Protocol.HTTP_1_1))
                    if (listener != null) {
                        // PINGs notice a connection the peer dropped without a GOAWAY/RST; ignored
                        // on HTTP/1.1.
                        pingInterval(20, TimeUnit.SECONDS)
                        eventListener(listener)
                    }
                }
            }
        }
    }

    private fun clientProxied(key: String, http1: Boolean, expectSuccess: Boolean): HttpClient {
        val stream = isStreamKey(key)
        val pool = newPool(stream)
        val listener = if (stream) ConnectionStatsListener(roleOf(key)) else null
        val proxyEnv = System.getenv("http_proxy") ?: System.getenv("HTTP_PROXY")
        logger.info("create proxied client key={} proxy={} http1={} expectSuccess={}", key, proxyEnv, http1, expectSuccess)
        return HttpClient(OkHttp) {
            configureClient()
            this.expectSuccess = expectSuccess
            install(ContentNegotiation) {
                json()
            }

            install(WebSockets) {
            }

            engine {
                if (proxyEnv != null) {
                    val url = Url(proxyEnv)
                    proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(url.host, url.port))
                }
                config {
                    connectionPool(pool)
                    followSslRedirects(true)
                    followRedirects(true)
                    if (http1) protocols(listOf(Protocol.HTTP_1_1))
                    if (listener != null) {
                        pingInterval(20, TimeUnit.SECONDS)
                        eventListener(listener)
                    }
                }
            }
        }
    }

    /**
     * Clients for one base key, split by the flags they were built with. The cache key must include
     * `http1`/`expectSuccess`: before this, a client built for one flag set was handed to callers
     * asking for another, so the first caller silently won for the whole process.
     */
    private class ClientCache {
        private val byFlags = HashMap<String, HttpClient>()
        fun get(http1: Boolean, expectSuccess: Boolean, create: () -> HttpClient): HttpClient =
            byFlags.getOrPut("http1=$http1,expectSuccess=$expectSuccess") { create() }
        fun closeAll() {
            byFlags.values.forEach { runCatching { it.close() } }
        }
    }

    private val clientsProxied = HashMap<String, ClientCache>()
    private val clientsDirect = HashMap<String, ClientCache>()
    private val lock = Any()

    private fun HttpClientConfig<OkHttpConfig>.configureClient() {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    if (this@ClientManager.logger.isTraceEnabled)
                        this@ClientManager.logger.trace(message.replace("\n", " "))
                }
            }
            level = LogLevel.INFO
        }
        install(WebSockets)
        install(HttpRequestRetry) {
            // Retry transport failures (incl. timeouts) in-place, but NOT HTTP status errors:
            // a 404/4xx must surface immediately (expired assignments are permanent and
            // the segment-level retry loop switches host instead of burning time here).
            retryOnExceptionIf(maxRetries = 3) { _, cause ->
                cause !is ResponseException
            }
            constantDelay(300)
        }
        install(DefaultRequest.Plugin) {
            headers {
                append(
                    HttpHeaders.Accept,
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
                )
                append(
                    HttpHeaders.UserAgent,
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:146.0) Gecko/20100101 Firefox/146.0"
                )
                append(HttpHeaders.AcceptLanguage, "en,zh-CN;q=0.9,zh;q=0.8")
                append(HttpHeaders.Connection, "keep-alive")
                // browser navigation fingerprint — required by the stripchat WAF on HTTP/1.1
                append("Sec-Fetch-Dest", "document")
                append("Sec-Fetch-Mode", "navigate")
                append("Sec-Fetch-Site", "none")
                append("Sec-Fetch-User", "?1")
                append("Upgrade-Insecure-Requests", "1")
            }
        }
    }

    fun getClient(key: String): HttpClient = getClient(key, http1 = false)

    fun getClient(key: String, http1: Boolean, expectSuccess: Boolean = true): HttpClient {
        synchronized(lock) {
            return clientsDirect.getOrPut(key) { ClientCache() }.get(http1, expectSuccess) {
                clientDirect(key, http1, expectSuccess)
            }
        }
    }

    fun getProxiedClient(key: String): HttpClient = getProxiedClient(key, http1 = false)

    fun getProxiedClient(key: String, http1: Boolean, expectSuccess: Boolean = true): HttpClient {
        synchronized(lock) {
            return clientsProxied.getOrPut(key) { ClientCache() }.get(http1, expectSuccess) {
                clientProxied(key, http1, expectSuccess)
            }
        }
    }

    /**
     * Close and forget a client, so its pooled connections die with it and the next request dials
     * fresh. Called when a request failed on a connection that may itself be the problem, and when
     * a room's streams end: a client kept across sessions would keep handing the same dead pooled
     * connection to every retry.
     */
    fun removeClient(key: String) {
        synchronized(lock) {
            clientsProxied.remove(key)?.let { cache ->
                cache.closeAll()
                logger.debug("Evicted proxied client {}", key)
            }
            clientsDirect.remove(key)?.let { cache ->
                cache.closeAll()
                logger.debug("Evicted direct client {}", key)
            }
        }
    }

    /** Close and remove every client a room's recording may have created. */
    fun removeRoomClients(roomId: Long) {
        listOf("m3u8", "master", "preconfig").forEach { kind ->
            val key = "${kind}_$roomId"
            // Log the id through the roomId key (and the kind separately): the masking rule only
            // covers `roomId=`, so printing the bare cache key would leak the id as `m3u8_1001`.
            synchronized(lock) { if (clientsProxied.containsKey(key)) logger.info("roomId={} closed per-room client kind={}", roomId, kind) }
            removeClient(key)
        }
    }

    /** Close every cached client. Called once on shutdown. */
    fun close() {
        synchronized(lock) {
            clientsProxied.values.forEach { it.closeAll() }
            clientsDirect.values.forEach { it.closeAll() }
            clientsProxied.clear()
            clientsDirect.clear()
        }
    }
}
