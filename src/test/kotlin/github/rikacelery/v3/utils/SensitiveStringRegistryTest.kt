package github.rikacelery.v3.utils

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `a room id masks to the same value as its name`() {
        val token = SensitiveStringRegistry.maskRoom(4242L, "room-mask-model")
        assertEquals(token, SensitiveStringRegistry.mask("room-mask-model"))
        assertEquals(token, SensitiveStringRegistry.maskPattern("4242"))
    }

    /**
     * A room id is pure digits, so it must stay out of the every-occurrence pass: otherwise
     * `1001` would rewrite `10012`, byte counts and every other unrelated number.
     */
    @Test
    fun `a room id is never substring-replaced`() {
        SensitiveStringRegistry.maskRoom(1001L, "room-mask-substring")
        assertEquals("segments=10012", SensitiveStringRegistry.maskText("segments=10012"))
        assertFalse(SensitiveStringRegistry.maskText("segments=10012").contains(SensitiveStringRegistry.maskPattern("1001")))
    }
}
