package github.rikacelery.v3.utils

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

class HostFailoverTest {

    private val failover = HostFailover()

    @BeforeEach
    fun setUp() {
        failover.updateHosts(emptyList())
    }

    @Test
    fun `updateHosts sanitizes and dedupes`() {
        failover.updateHosts(listOf(" a.com ", "a.com", "", " b.com/", "b.com"))
        assertEquals(listOf("a.com", "b.com"), failover.hosts)
    }

    @Test
    fun `currentHost returns first available host`() {
        failover.updateHosts(listOf("a.com", "b.com", "c.com"))
        assertEquals("a.com", failover.currentHost())
    }

    @Test
    fun `markFailure advances to next available host`() {
        failover.updateHosts(listOf("a.com", "b.com", "c.com"))
        failover.markFailure("a.com", now = 0)
        assertEquals("b.com", failover.currentHost(now = 1_000))
        failover.markFailure("b.com", now = 1_000)
        assertEquals("c.com", failover.currentHost(now = 2_000))
    }

    @Test
    fun `host returns to rotation after cooldown expires`() {
        failover.updateHosts(listOf("a.com", "b.com"))
        failover.markFailure("a.com", now = 0)
        assertEquals("b.com", failover.currentHost(now = 4_000))
        assertEquals("a.com", failover.currentHost(now = 6_000))
    }

    @Test
    fun `markSuccess resets failure state`() {
        failover.updateHosts(listOf("a.com", "b.com"))
        failover.markFailure("a.com", now = 0)
        failover.markSuccess("a.com")
        assertEquals("a.com", failover.currentHost(now = 1_000))
    }
}

class CdnSelectorTest {

    @BeforeEach
    fun setUp() {
        CdnSelector.reset()
        CdnSelector.updateHosts(listOf("cdn-a.com", "cdn-b.com"))
        CdnSelector.exploreProbability = 0.0
    }

    @Test
    fun `select returns a configured host when nothing is measured`() {
        val host = CdnSelector.select()
        assertTrue(host in listOf("cdn-a.com", "cdn-b.com"))
    }

    @Test
    fun `select picks the fastest host without exploration`() {
        CdnSelector.record("cdn-a.com", bytes = 1_000_000, durationMs = 1_000) // 1 MB/s
        CdnSelector.record("cdn-b.com", bytes = 2_000_000, durationMs = 1_000) // 2 MB/s
        repeat(200) { assertEquals("cdn-b.com", CdnSelector.select()) }
    }

    @Test
    fun `exploration assigns some selections to other hosts`() {
        CdnSelector.record("cdn-a.com", bytes = 1_000_000, durationMs = 1_000)
        CdnSelector.record("cdn-b.com", bytes = 2_000_000, durationMs = 1_000)
        // with epsilon = 0.5 both the fastest (b) and the other host (a) must appear
        CdnSelector.exploreProbability = 0.5
        val picked = (1..200).map { CdnSelector.select() }.toSet()
        assertEquals(setOf("cdn-a.com", "cdn-b.com"), picked)
    }

    @Test
    fun `failed host is skipped while cooling down`() {
        CdnSelector.recordFailure("cdn-a.com", now = 0)
        assertEquals("cdn-b.com", CdnSelector.select(now = 1_000))
        // after cooldown expires it can be picked again
        assertTrue(CdnSelector.select(now = 1_000_000) in listOf("cdn-a.com", "cdn-b.com"))
    }

    @Test
    fun `rewriteHost replaces host and keeps path and query`() {
        val url = "https://cdn-a.com/b-hls-22/123/123_720p.m3u8?psch=v2&pkey=k1"
        val rewritten = CdnSelector.rewriteHost(url, "cdn-b.com")
        assertEquals("https://cdn-b.com/b-hls-22/123/123_720p.m3u8?psch=v2&pkey=k1", rewritten)
    }

    @Test
    fun `hostOf extracts host from url`() {
        assertEquals("cdn-a.com", CdnSelector.hostOf("https://cdn-a.com/x/y.m3u8?z=1"))
        assertEquals("cdn-b.com", CdnSelector.hostOf("https://cdn-b.com/b-hls-22/1/1.m3u8"))
    }

    @Test
    fun `ewma speed converges toward recent samples`() {
        CdnSelector.record("cdn-a.com", bytes = 1_000_000, durationMs = 1_000)
        CdnSelector.record("cdn-a.com", bytes = 10_000_000, durationMs = 1_000)
        CdnSelector.record("cdn-a.com", bytes = 10_000_000, durationMs = 1_000)
        CdnSelector.record("cdn-a.com", bytes = 10_000_000, durationMs = 1_000)
        val speed = CdnSelector.snapshot()["cdn-a.com"]?.speedBps ?: 0.0
        assertTrue(speed > 5_000_000, "speed should be pulled toward the recent 10MB/s samples, was $speed")
    }
}
