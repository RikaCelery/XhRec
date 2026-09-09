package github.rikacelery.v3.api

import github.rikacelery.v3.exceptions.DeletedException
import github.rikacelery.v3.exceptions.RenameException
import github.rikacelery.v3.utils.HttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.*

class ApiClientTest {

    @Test
    fun `api clients keep platform hosts independent`() {
        val firstHosts = mutableListOf(
            " first.example.com/ ",
            "first.example.com",
            " backup.example.com// "
        )
        val first = ApiClient(firstHosts)
        val second = ApiClient(listOf("second.example.com"))

        firstHosts.clear()
        firstHosts += "caller-mutated.example.com"
        second.applyHosts(listOf("second-mirror.example.com"))

        assertEquals(listOf("first.example.com", "backup.example.com"), first.platformHosts)
        assertEquals(listOf("second-mirror.example.com"), second.platformHosts)
        assertEquals(listOf(ApiClient.DEFAULT_PLATFORM_HOST), ApiClient(emptyList()).platformHosts)
    }

    @Test
    fun `guest token request uses injected network boundary`() = runTest {
        var requestedUrl = ""
        val mockClient = HttpClient(MockEngine { request ->
            requestedUrl = request.url.toString()
            respond(
                content = """{"initial":{"client":{"websocket":{"token":"guest-token"}}}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        })
        try {
            var proxiedRequest: Triple<String, Boolean, Boolean>? = null
            val provider = object : HttpClientProvider {
                override fun direct(key: String, http1: Boolean, expectSuccess: Boolean): HttpClient =
                    error("direct client not expected")

                override fun proxied(key: String, http1: Boolean, expectSuccess: Boolean): HttpClient {
                    proxiedRequest = Triple(key, http1, expectSuccess)
                    return mockClient
                }
            }
            val client = ApiClient(
                initialHosts = listOf("platform.test"),
                httpClientProvider = provider,
                baseUrlBuilder = { host -> "http://$host:18080" }
            )

            assertEquals("guest-token", client.fetchGuestWsToken())
            assertEquals(Triple("api", true, false), proxiedRequest)
            assertEquals("http://platform.test:18080/api/front/v3/config/initial", requestedUrl)
        } finally {
            mockClient.close()
        }
    }

    @Test
    fun `404 rename description throws RenameException with new name`() {
        val e = assertFailsWith<RenameException> {
            throwBroadcast404("""{"description":"Model has new name: newName=NewModel123"}""")
        }
        assertEquals("NewModel123", e.newName)
    }

    @Test
    fun `404 deleted description throws DeletedException`() {
        assertFailsWith<DeletedException> {
            throwBroadcast404("""{"description":"model already deleted"}""")
        }
    }

    @Test
    fun `404 non-json body throws IllegalStateException`() {
        assertFailsWith<IllegalStateException> {
            throwBroadcast404("<html>cloudflare block page</html>")
        }
    }

    @Test
    fun `404 unknown description throws IllegalStateException`() {
        assertFailsWith<IllegalStateException> {
            throwBroadcast404("""{"description":"something unexpected"}""")
        }
    }
}
