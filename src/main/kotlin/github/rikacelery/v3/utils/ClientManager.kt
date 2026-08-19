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
     * http1=true forces HTTP/1.1 — required for the stripchat.com WAF: its HTTP/2
     * fingerprint check rejects OkHttp (non-browser h2), while HTTP/1.1 + browser
     * navigation headers passes. CDN clients (doppiocdn.org) keep HTTP/2.
     */
    private fun clientDirect(key: String, http1: Boolean, expectSuccess: Boolean): HttpClient {
        val pool = ConnectionPool(16, 5, TimeUnit.MINUTES)
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
                }
            }
        }
    }

    private fun clientProxied(key: String, http1: Boolean, expectSuccess: Boolean): HttpClient {
        val pool = ConnectionPool(16, 5, TimeUnit.MINUTES)
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
                }
            }
        }
    }

    private val clientsProxied = HashMap<String, HttpClient>()
    private val clientsDirect = HashMap<String, HttpClient>()
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
            retryOnException(maxRetries = 3, retryOnTimeout = true)
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
            return clientsDirect[key] ?: clientDirect(key, http1, expectSuccess).also { clientsDirect[key] = it }
        }
    }

    fun getProxiedClient(key: String): HttpClient = getProxiedClient(key, http1 = false)

    fun getProxiedClient(key: String, http1: Boolean, expectSuccess: Boolean = true): HttpClient {
        synchronized(lock) {
            return clientsProxied[key] ?: clientProxied(key, http1, expectSuccess).also { clientsProxied[key] = it }
        }
    }

    /** Close and remove the per-room clients created for a recording session. */
    fun removeRoomClients(roomId: Long) {
        synchronized(lock) {
            listOf("m3u8_$roomId", "master_$roomId").forEach { key ->
                clientsProxied.remove(key)?.let { client ->
                    runCatching { client.close() }
                    logger.info("Closed per-room client {}", key)
                }
            }
        }
    }

    fun close() {
        clientsProxied.forEach {
            it.value.close()
        }
        clientsDirect.forEach {
            it.value.close()
        }
    }
}
