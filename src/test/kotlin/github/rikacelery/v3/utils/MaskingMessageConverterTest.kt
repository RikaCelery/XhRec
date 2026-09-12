package github.rikacelery.v3.utils

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MaskingMessageConverterTest {

    private fun convert(message: String): String {
        // The registry flag is process-wide and other suites toggle it; force it on for this test
        // and restore, so the result does not depend on test execution order.
        val previous = SensitiveStringRegistry.enabled
        SensitiveStringRegistry.enabled = true
        try {
            val context = LoggerFactory.getILoggerFactory() as LoggerContext
            val event = LoggingEvent("fqcn", context.getLogger("masking-test"), Level.INFO, message, null, null)
            return MaskingMessageConverter().convert(event)
        } finally {
            SensitiveStringRegistry.enabled = previous
        }
    }

    @Test
    fun `a room id is masked with the same token as its model name`() {
        val token = SensitiveStringRegistry.maskRoom(2002L, "masking-test-model")
        val out = convert("roomId=2002 name=masking-test-model")
        assertTrue(out.contains("roomId=$token"), out)
        assertTrue(out.contains("name=$token"), out)
        assertFalse(out.contains("roomId=2002"), out)
    }

    @Test
    fun `only the roomId pattern is replaced, not every number`() {
        SensitiveStringRegistry.maskRoom(2003L, "masking-test-other")
        val out = convert("roomId=2003 segments=20034 bytes=12003")
        assertFalse(out.contains("roomId=2003"), out)
        assertTrue(out.contains("segments=20034"), out)
        assertTrue(out.contains("bytes=12003"), out)
    }

    @Test
    fun `quoted roomId forms are masked too`() {
        val token = SensitiveStringRegistry.maskRoom(2004L, "masking-test-quoted")
        assertTrue(convert("roomId=\"2004\"").contains("roomId=\"$token\""))
        assertTrue(convert("\"roomId\":2004").contains("\"roomId\":$token"))
    }

    @Test
    fun `room ids inside http urls are masked with the room token`() {
        val token = SensitiveStringRegistry.maskRoom(3003L, "masking-test-url")
        val out = convert("GET https://cdn.test/hls/3003/master/3003_auto.m3u8?psch=v2&pkey=secret")
        assertFalse(out.contains("3003"), out)
        assertTrue(out.contains("/hls/$token/master/${token}_auto.m3u8"), out)
        assertTrue(out.contains("pkey=***"), out)
        assertTrue(out.contains("psch=v2"), out)

        // CDN segment name: the room id is the file-name prefix; the segment id/timestamp behind it
        // are not path segments and must survive.
        val segment = convert("url=https://cdn.test/3003_480p_h264_iRAiezcS7w4MfUvZ_1779960801.mp4")
        assertFalse(segment.contains("3003"), segment)
        assertTrue(segment.contains("_iRAiezcS7w4MfUvZ_1779960801.mp4"), segment)
    }

    @Test
    fun `numbers outside a url are left alone`() {
        assertEquals("segments=10012", convert("segments=10012"))
    }
}
