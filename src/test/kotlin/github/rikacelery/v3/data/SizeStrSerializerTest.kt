package github.rikacelery.v3.data

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The `/add?size=` and `/sizelimit` routes parse user input with this serializer, and
 * [SizeStrSerializer.serialize] writes back the same two-letter binary units it must accept.
 */
class SizeStrSerializerTest {

    @Test
    fun `binary units parse case-insensitively`() {
        assertEquals(1024L, SizeStrSerializer.parseSizeString("1Ki"))
        assertEquals(1024L, SizeStrSerializer.parseSizeString("1ki"))
        assertEquals(1024L, SizeStrSerializer.parseSizeString("1KI"))
        assertEquals(1024L * 1024, SizeStrSerializer.parseSizeString("1Mi"))
        assertEquals(1024L * 1024 * 1024, SizeStrSerializer.parseSizeString("1Gi"))
        assertEquals(1024L * 1024 * 1024 * 1024, SizeStrSerializer.parseSizeString("1Ti"))
        assertEquals(512L, SizeStrSerializer.parseSizeString("512Bi"))
        assertEquals(2048L, SizeStrSerializer.parseSizeString("2Ki"))
        assertEquals(0L, SizeStrSerializer.parseSizeString("0"))
    }

    @Test
    fun `single-letter units and compounds parse`() {
        // single letters resolve through the binary table first (K/G/M/T = Ki/Gi/Mi/Ti)
        assertEquals(1024L, SizeStrSerializer.parseSizeString("1K"))
        assertEquals(1024L * 1024 * 1024, SizeStrSerializer.parseSizeString("1G"))
        assertEquals(1024L * 1024 * 1024 + 500L * 1024 * 1024, SizeStrSerializer.parseSizeString("1G500M"))
    }

    @Test
    fun `serialized sizes round-trip through the parser`() {
        listOf(512L, 2048L, 5L * 1024 * 1024, 3L * 1024 * 1024 * 1024).forEach { bytes ->
            val encoded = Json.encodeToString(SizeStrSerializer, bytes).trim('"')
            assertEquals(bytes, SizeStrSerializer.parseSizeString(encoded), "round-trip of '$encoded'")
        }
    }

    @Test
    fun `unknown units and missing numbers are rejected`() {
        assertFailsWith<IllegalArgumentException> { SizeStrSerializer.parseSizeString("1Xi") }
        assertFailsWith<IllegalArgumentException> { SizeStrSerializer.parseSizeString("Ki") }
    }
}
