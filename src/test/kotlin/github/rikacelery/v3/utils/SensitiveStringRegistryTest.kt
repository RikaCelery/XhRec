package github.rikacelery.v3.utils

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SensitiveStringRegistryTest {

    @Test
    fun `mask is stable for the same input`() {
        val masked = SensitiveStringRegistry.mask("sensitive-alpha")
        assertEquals(masked, SensitiveStringRegistry.mask("sensitive-alpha"))
    }

    @Test
    fun `maskText replaces every occurrence`() {
        val masked = SensitiveStringRegistry.mask("sensitive-beta")
        assertEquals(
            "a $masked b $masked",
            SensitiveStringRegistry.maskText("a sensitive-beta b sensitive-beta")
        )
    }

    /**
     * The snapshot used by [SensitiveStringRegistry.maskText] is sorted longest-first, so a shorter
     * registered name that is a prefix of a longer one cannot partially mask the longer name.
     */
    @Test
    fun `maskText prefers the longest registered string`() {
        val short = SensitiveStringRegistry.mask("sensitive-gamma")
        val long = SensitiveStringRegistry.mask("sensitive-gamma-extended")
        assertNotEquals(short, long)
        assertEquals(
            "room $long here",
            SensitiveStringRegistry.maskText("room sensitive-gamma-extended here")
        )
    }

    @Test
    fun `maskText leaves unregistered text untouched`() {
        assertEquals("nothing to mask", SensitiveStringRegistry.maskText("nothing to mask"))
    }
}
