package github.rikacelery.v3.components

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CircleCacheTest {

    @Test
    fun `add reports new urls and rejects repeats`() {
        val cache = CircleCache(4)
        assertTrue(cache.add("init-1"))
        assertFalse(cache.add("init-1"))
    }

    /**
     * A full cache must evict the oldest entry, not clear itself: clearing made the next `add` of
     * the constantly re-listed init URL report as new, which re-injected the init into the file.
     */
    @Test
    fun `full cache evicts only the oldest entry`() {
        val cache = CircleCache(2)
        assertTrue(cache.add("a"))
        assertTrue(cache.add("b"))

        assertTrue(cache.add("c"), "c is new")
        assertFalse(cache.add("b"), "b is still cached after one eviction")
        assertTrue(cache.add("a"), "a was the oldest and is new again")
    }
}
