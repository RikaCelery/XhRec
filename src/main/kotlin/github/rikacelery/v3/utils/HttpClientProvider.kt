package github.rikacelery.v3.utils

import io.ktor.client.HttpClient

interface HttpClientProvider {
    fun direct(key: String, http1: Boolean = false, expectSuccess: Boolean = true): HttpClient
    fun proxied(key: String, http1: Boolean = false, expectSuccess: Boolean = true): HttpClient
}

object DefaultHttpClientProvider : HttpClientProvider {
    override fun direct(key: String, http1: Boolean, expectSuccess: Boolean): HttpClient =
        ClientManager.getClient(key, http1, expectSuccess)

    override fun proxied(key: String, http1: Boolean, expectSuccess: Boolean): HttpClient =
        ClientManager.getProxiedClient(key, http1, expectSuccess)
}
