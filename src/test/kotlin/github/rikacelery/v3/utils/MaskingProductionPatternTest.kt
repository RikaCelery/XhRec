package github.rikacelery.v3.utils

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.OutputStreamAppender
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The masking that matters is the one the *production* configuration applies, not the converter on
 * its own: the pattern decides whether an exception's stack trace is printed, and logback appends a
 * throwable converter of its own when the pattern names none — a converter the message masking never
 * reaches, which is how a signed url, the room id inside its path and a `Cookie:` header used to
 * reach the log through a Ktor failure. So this renders events through the real patterns of the
 * configuration the jar carries and checks that nothing sensitive survives.
 */
class MaskingProductionPatternTest {

    private val roomId = 7654321L
    private val roomName = "masking-pattern-model"
    private val cookie = "PHPSESSID=deadbeefdeadbeefdeadbeef"
    private val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiItMTA4MSJ9.IXF36-UfCEmOPGvhl2a19rgLsh2rDCdXNJ3su9LkA9Y"
    private val streamKey = "pkey-secret-value"
    private val streamToken = "aclAuth-secret-value"
    private val proxy = "http://proxy-user:proxy-pass@proxy.internal:8080"

    private val playlistUrl =
        "https://cdn.test/hls/$roomId/media/${roomId}_720p.m3u8?psch=v2&pkey=$streamKey&aclAuth=$streamToken"

    /** Every literal that must not survive a trip through the log pipeline. */
    private fun secrets(): Map<String, String> = linkedMapOf(
        "room id" to roomId.toString(),
        "model name" to roomName,
        "cookie value" to cookie,
        "jwt" to jwt,
        "pkey value" to streamKey,
        "aclAuth value" to streamToken,
        "proxy credentials" to "proxy-user:proxy-pass"
    )

    /**
     * The layouts of one configuration, loaded as a context of its own (the running test loggers stay
     * untouched) with its file appender pointed at a temporary directory: a test must not open the
     * real `logs/xhrec.log`.
     */
    private fun layoutsOf(rawConfig: String): List<PatternLayout> {
        val tempDir = java.nio.file.Files.createTempDirectory("xhrec-masking")
        val config = rawConfig
            .replace("scan=\"true\"", "scan=\"false\"")
            .replace("logs/xhrec", tempDir.resolve("xhrec").toString())
        val context = LoggerContext()
        JoranConfigurator().apply { this.context = context }.doConfigure(config.byteInputStream())
        val layouts = context.getLogger(Logger.ROOT_LOGGER_NAME).iteratorForAppenders().asSequence()
            .filterIsInstance<OutputStreamAppender<ILoggingEvent>>()
            .mapNotNull { it.encoder as? PatternLayoutEncoder }
            .map { it.layout as PatternLayout }
            .toList()
        assertTrue(layouts.size >= 2, "a configuration must define the console and file patterns")
        return layouts
    }

    /**
     * The configuration the jar itself carries — the one every deployment starts from, and the only
     * one under version control. A container may mount a config of its own; that file is not part of
     * the repository, so it is not part of this check.
     */
    private fun deploymentLayouts(): List<PatternLayout> {
        val packaged = javaClass.getResourceAsStream("/logback.xml")?.readBytes()?.decodeToString()
            ?: error("the packaged logback.xml is not on the test classpath")
        return layoutsOf(packaged)
    }

    private fun render(message: String, throwable: Throwable? = null): String {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        return deploymentLayouts().joinToString("\n") { layout ->
            layout.doLayout(
                LoggingEvent("fqcn", context.getLogger("masking-pattern-test"), Level.WARN, message, throwable, null)
            )
        }
    }

    private fun assertMasked(rendered: String, what: String) {
        secrets().forEach { (label, value) ->
            assertFalse(rendered.contains(value), "$label ($what) leaked into the log line: $rendered")
        }
    }

    @Test
    fun `a recorded room, its url and its credentials are masked`() {
        val previous = SensitiveStringRegistry.enabled
        SensitiveStringRegistry.enabled = true
        try {
            val mask = SensitiveStringRegistry.maskRoom(roomId, roomName)
            val rendered = render("roomId=$roomId status public -> private, fetching $playlistUrl")
            assertMasked(rendered, "message")
            // the room id and its name share one mask, so a line that mentions both stays correlatable
            assertTrue(rendered.contains(mask), "the mask must be what is left: $rendered")
            assertFalse(rendered.contains("/hls/$roomId/"), "the id inside the url path must be masked too: $rendered")
        } finally {
            SensitiveStringRegistry.enabled = previous
        }
    }

    @Test
    fun `cookies, tokens and proxy credentials are masked`() {
        val previous = SensitiveStringRegistry.enabled
        SensitiveStringRegistry.enabled = true
        try {
            val rendered = render("Cookie: $cookie auth=$jwt proxy=$proxy")
            assertMasked(rendered, "message")
            assertTrue(rendered.contains("Cookie: ***"), rendered)
            assertTrue(rendered.contains("***jwt***"), rendered)
        } finally {
            SensitiveStringRegistry.enabled = previous
        }
    }

    /**
     * The case the message converter cannot reach on its own: an exception whose text carries the
     * signed url — Ktor builds exactly that for a failed request — printed as the stack trace the
     * pattern appends.
     */
    @Test
    fun `an exception that carries a signed url is masked in the stack trace`() {
        val previous = SensitiveStringRegistry.enabled
        SensitiveStringRegistry.enabled = true
        try {
            SensitiveStringRegistry.maskRoom(roomId, roomName)
            val failure = IllegalStateException(
                "Client request(GET $playlistUrl) invalid: 403 Forbidden, Cookie: $cookie"
            )
            val rendered = render("preconfig failed roomId=$roomId", failure)
            assertMasked(rendered, "throwable")
            // masked, not dropped: an operator still needs to know what failed and where
            assertTrue(rendered.contains("java.lang.IllegalStateException"), "the trace must survive: $rendered")
            assertTrue(
                rendered.contains("MaskingProductionPatternTest"),
                "the trace must still name the frame it came from: $rendered"
            )
        } finally {
            SensitiveStringRegistry.enabled = previous
        }
    }

    @Test
    fun `masking off leaves the line as written`() {
        val previous = SensitiveStringRegistry.enabled
        SensitiveStringRegistry.enabled = false
        try {
            val rendered = render("roomId=$roomId fetching $playlistUrl")
            assertEquals(true, rendered.contains(streamToken), "with masking off the operator asked for raw lines")
        } finally {
            SensitiveStringRegistry.enabled = previous
        }
    }
}
