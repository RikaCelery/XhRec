package github.rikacelery.v3.utils

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Playlist failures are tracked apart from segment failures: a host can serve segments perfectly
 * while its playlist requests time out, and the segment successes of the other rooms would clear a
 * shared counter before it could ever cool the host down.
 */
class CdnPlaylistCooldownTest {

    private val zone = ZoneId.systemDefault()
    private val now = ZonedDateTime.of(2024, 1, 15, 10, 30, 0, 0, zone).toInstant().toEpochMilli()

    @BeforeEach
    fun setUp() {
        CdnSelector.reset()
        CdnSelector.updateHosts(listOf("cdn-a.com", "cdn-b.com"))
        CdnSelector.exploreProbability = 0.0
        CdnSelector.spreadTolerance = 0.0
        CdnSelector.spreadAbsMs = 0.0
    }

    @Test
    fun `three playlist failures cool the host down for playlists only`() {
        CdnSelector.recordPlaylistFailure("cdn-a.com", now)
        CdnSelector.recordPlaylistFailure("cdn-a.com", now)
        assertEquals(0L, CdnSelector.snapshot(now)["cdn-a.com"]!!.playlistCooldownUntil)

        CdnSelector.recordPlaylistFailure("cdn-a.com", now)
        val snap = CdnSelector.snapshot(now)["cdn-a.com"]!!
        assertEquals(3, snap.playlistFailures)
        assertTrue(snap.playlistCooldownUntil > now, "3 consecutive playlist failures should cool down")
        // the segment view of the host is untouched: it is still selectable for downloads
        assertEquals(0L, snap.cooldownUntil)
        assertTrue(CdnSelector.rankedHosts(now).contains("cdn-a.com"))
        assertFalse(CdnSelector.rankedPlaylistHosts(now).contains("cdn-a.com"))
    }

    @Test
    fun `segment success neither clears nor triggers the playlist penalty`() {
        CdnSelector.recordPlaylistFailure("cdn-a.com", now)
        CdnSelector.recordPlaylistFailure("cdn-a.com", now)
        // A segment served by another room is not evidence about playlists.
        CdnSelector.record("cdn-a.com", 150, now)
        val afterSuccess = CdnSelector.snapshot(now)["cdn-a.com"]!!
        assertEquals(2, afterSuccess.playlistFailures, "a segment success must not clear playlist failures")
        assertEquals(0, afterSuccess.failures)

        CdnSelector.recordPlaylistFailure("cdn-a.com", now)
        assertTrue(CdnSelector.snapshot(now)["cdn-a.com"]!!.playlistCooldownUntil > now)

        // …and once cooled down, more segment successes must not release it.
        CdnSelector.record("cdn-a.com", 150, now)
        assertTrue(CdnSelector.snapshot(now)["cdn-a.com"]!!.playlistCooldownUntil > now)

        CdnSelector.recordPlaylistSuccess("cdn-a.com")
        val cleared = CdnSelector.snapshot(now)["cdn-a.com"]!!
        assertEquals(0, cleared.playlistFailures)
        assertEquals(0L, cleared.playlistCooldownUntil)
    }

    @Test
    fun `playlist selection skips a playlist-cooled host that is still best for segments`() {
        // cdn-a is the better download host, so the segment selector keeps choosing it…
        repeat(5) {
            CdnSelector.record("cdn-a.com", 100, now)
            CdnSelector.record("cdn-b.com", 300, now)
        }
        repeat(3) { CdnSelector.recordPlaylistFailure("cdn-a.com", now) }

        assertEquals("cdn-a.com", CdnSelector.select(now), "segments should still prefer cdn-a")
        assertEquals("cdn-b.com", CdnSelector.selectPlaylist(now), "playlists should avoid cdn-a")

        val url = "https://cdn-a.com/b-hls-22/123/123_720p.m3u8?psch=v2&pkey=k1"
        assertTrue(CdnSelector.resolvePlaylist(url, now).startsWith("https://cdn-b.com/"))
        assertTrue(CdnSelector.resolve(url, now).startsWith("https://cdn-a.com/"))
        assertEquals(listOf("cdn-b.com"), CdnSelector.rankedPlaylistHosts(now))
    }

    @Test
    fun `playlist penalty survives a persistence round trip`() {
        repeat(3) { CdnSelector.recordPlaylistFailure("cdn-a.com", now) }
        val exported = CdnSelector.exportState()

        CdnSelector.reset()
        CdnSelector.importState(exported)

        val restored = CdnSelector.snapshot(now)["cdn-a.com"]!!
        assertEquals(3, restored.playlistFailures)
        assertTrue(restored.playlistCooldownUntil > now)
        assertFalse(CdnSelector.playlistAvailableHosts(now).contains("cdn-a.com"))
    }

    @Test
    fun `clearCooldown releases the playlist penalty too`() {
        repeat(3) { CdnSelector.recordPlaylistFailure("cdn-a.com", now) }
        assertTrue(CdnSelector.snapshot(now)["cdn-a.com"]!!.playlistCooldownUntil > now)

        CdnSelector.clearCooldown("cdn-a.com")
        val cleared = CdnSelector.snapshot(now)["cdn-a.com"]!!
        assertEquals(0, cleared.playlistFailures)
        assertEquals(0L, cleared.playlistCooldownUntil)
        assertTrue(CdnSelector.playlistAvailableHosts(now).contains("cdn-a.com"))
    }
}
