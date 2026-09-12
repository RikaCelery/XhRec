package github.rikacelery.v3.utils

import io.ktor.client.HttpClient

interface HttpClientProvider {
    fun direct(key: String, http1: Boolean = false, expectSuccess: Boolean = true): HttpClient
    fun proxied(key: String, http1: Boolean = false, expectSuccess: Boolean = true): HttpClient

    /**
     * Drop [key]'s pooled connections so the next request dials fresh. Used after a request failed
     * in a way that may implicate the connection itself (a timeout on a pooled connection) and
     * when a room's streams end. Default no-op so test providers need no change.
     */
    fun evict(key: String) {}
}

object DefaultHttpClientProvider : HttpClientProvider {
    override fun direct(key: String, http1: Boolean, expectSuccess: Boolean): HttpClient =
        ClientManager.getClient(key, http1, expectSuccess)

    override fun proxied(key: String, http1: Boolean, expectSuccess: Boolean): HttpClient =
        ClientManager.getProxiedClient(key, http1, expectSuccess)

    override fun evict(key: String) = ClientManager.removeClient(key)
}
