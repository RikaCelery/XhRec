package github.rikacelery.v3.api

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import github.rikacelery.v3.utils.HttpClientProvider
import github.rikacelery.v3.utils.SensitiveStringRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every platform API call reports one DEBUG line — `code path bodyLength` — and that line is what an
 * operator reads when a room's own state cannot tell two very different answers apart: a `200` with
 * an empty `cam.modelToken` and a `403` with an error body both end up as "no token" (issue #192).
 *
 * The path is masked on the way out, so a line pasted into an issue carries no raw room id.
 */
class ApiClientCallLogTest {

    @Test
    fun `a successful call logs its status, masked path and body length`() = runTest {
        val body = """{"cam":{"modelToken":""},"user":{"user":{"id":7654321}}}"""
        val client = client(HttpStatusCode.OK, body)
        try {
            withApiLog { lines ->
                ApiClient(listOf("platform.test"), singleClientProvider(client)) { "http://$it" }
                    .roomFetchCamInfo(7654321L, "cookie")

                assertEquals(
                    listOf("200 api/front/v2/models/${mask("7654321")}/cam ${body.length}"),
                    lines()
                )
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `a rejected call is logged once per attempt, with the body it answered`() = runTest {
        val body = """{"error":"User is unauthorized"}"""
        val client = client(HttpStatusCode.Forbidden, body)
        try {
            withApiLog { lines ->
                runCatching {
                    ApiClient(listOf("platform.test"), singleClientProvider(client)) { "http://$it" }
                        .roomFetchCamInfo(7654321L, "cookie")
                }

                val observed = lines()
                assertTrue(observed.isNotEmpty(), "a rejected call must still be logged")
                assertTrue(
                    observed.all { it == "403 api/front/v2/models/${mask("7654321")}/cam ${body.length}" },
                    "every attempt reports the same status, path and length: $observed"
                )
            }
        } finally {
            client.close()
        }
    }

    // —— harness ——

    /**
     * Runs [block] with the ApiClient logger captured at DEBUG; [lines] returns the DEBUG lines it
     * wrote (the same logger also warns about a failing host, which is not this test's subject).
     */
    private suspend fun withApiLog(block: suspend (lines: () -> List<String>) -> Unit) {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val logger = context.getLogger("v3.ApiClient")
        val previousLevel = logger.level
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        logger.level = Level.DEBUG
        try {
            block { appender.list.filter { it.level == Level.DEBUG }.map { it.formattedMessage } }
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
            appender.stop()
        }
    }

    private fun client(status: HttpStatusCode, body: String) = HttpClient(
        MockEngine {
            respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
    )

    private fun singleClientProvider(client: HttpClient) = object : HttpClientProvider {
        override fun direct(key: String, http1: Boolean, expectSuccess: Boolean): HttpClient = client
        override fun proxied(key: String, http1: Boolean, expectSuccess: Boolean): HttpClient = client
    }

    /** The mask the log pipeline uses for a bare path id, whichever way the registry computes it. */
    private fun mask(id: String): String {
        val previous = SensitiveStringRegistry.enabled
        SensitiveStringRegistry.enabled = true
        try {
            return SensitiveStringRegistry.maskPattern(id)
        } finally {
            SensitiveStringRegistry.enabled = previous
        }
    }
}
