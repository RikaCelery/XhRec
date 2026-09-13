package github.rikacelery.cutter

import github.rikacelery.cutter.media.MediaIndex
import github.rikacelery.cutter.media.RecordingName
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * File-name parsing against names taken verbatim from the production corpus.
 *
 * These are the anchor for every time mapping in the app: the recording start parsed
 * here is what event `createdAt` values are measured against, so a regression is not
 * cosmetic.
 */
class RecordingNameTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Test
    fun `parses the modern name shape`() {
        val stamp = RecordingName.parse("mss_mar-2026-08-27-170503-00h00m30s.fixed.mp4")
        assertEquals(LocalDateTime.of(2026, 8, 27, 17, 5, 3), stamp.startLocal)
        assertEquals(30L, stamp.durationSeconds)
    }

    @Test
    fun `parses hours minutes and seconds`() {
        // The NAS corpus writes this stamp with underscores and a spaced clock.
        val stamp = RecordingName.parse("Yaya--728-2026_03_18_19_58_50-00h06m03s.fixed.mp4")
        assertEquals(LocalDateTime.of(2026, 3, 18, 19, 58, 50), stamp.startLocal)
        assertEquals(363L, stamp.durationSeconds)
    }

    @Test
    fun `parses a minutes-and-seconds duration without hours`() {
        val stamp = RecordingName.parse("Ab324--2026-08-22-004724-2m2s.mp4")
        assertEquals(122L, stamp.durationSeconds)
    }

    @Test
    fun `parses an hours-only duration`() {
        val stamp = RecordingName.parse("room-2026-01-02-030405-3h.mp4")
        assertEquals(3 * 3600L, stamp.durationSeconds)
    }

    @Test
    fun `room names may contain digits and hyphens`() {
        assertEquals("xiao-Lin", RecordingName.room("xiao-Lin-2026-08-26-205610-00h23m23s.fixed.mp4"))
        assertEquals("office-diodio", RecordingName.room("office-diodio-2026-08-23-155755-00h49m04s.fixed.mp4"))
        assertEquals("mss_mar", RecordingName.room("mss_mar-2026-08-27-170503-00h00m30s.fixed.mp4"))
        assertEquals("NATAASHA___", RecordingName.room("NATAASHA___-2026-09-06-135721-00h21m46s.fixed.mp4"))
    }

    /**
     * `_Galax-` is a real room whose recordings are named `_Galax--2026-...`. The
     * separator before the year must not be trimmed, or the room loses its final
     * character and stops matching the directory it lives in.
     */
    @Test
    fun `room name keeps a trailing separator that belongs to it`() {
        assertEquals("_Galax-", RecordingName.room("_Galax--2026-09-06-132305-01h23m41s.fixed.mp4"))
    }

    @Test
    fun `a name without a duration still yields a start`() {
        val stamp = RecordingName.parse("Robin-o3-2026-09-10-221257-init.mp4")
        assertEquals(LocalDateTime.of(2026, 9, 10, 22, 12, 57), stamp.startLocal)
        assertNull(stamp.durationSeconds)
    }

    /**
     * The legacy name embeds `<start>-<end>`, so the *first* stamp is the start —
     * taking the last one would silently report the recording's end time as its
     * start and shift every event by the whole duration.
     */
    @Test
    fun `parses the legacy CJK stamp and takes the start not the end`() {
        val name = "[146535429]_Freydis_@2026年06月14日17时12分51秒-2026年06月14日17时59分38秒 00h46m47s.mp4"
        val stamp = RecordingName.parse(name)
        assertEquals(LocalDateTime.of(2026, 6, 14, 17, 12, 51), stamp.startLocal)
        assertEquals(46 * 60 + 47L, stamp.durationSeconds)
        assertEquals("_Freydis_", RecordingName.room(name))
    }

    @Test
    fun `uses the trailing stamp not a date-looking run inside the room name`() {
        val stamp = RecordingName.parse("cam-2026-01-01-2026-03-04-101112-00h01m00s.mp4")
        assertEquals(LocalDateTime.of(2026, 3, 4, 10, 11, 12), stamp.startLocal)
    }

    @Test
    fun `start epoch is interpreted in the configured zone`() {
        val stamp = RecordingName.parse("room-2026-01-02-030405-00h00m12s.mp4")
        // 2026-01-02 03:04:05 +08:00 == 2026-01-01 19:04:05 UTC
        assertEquals(1767294245L, stamp.startEpochSeconds(shanghai))
        // The same wall clock in UTC is 8 h later in absolute terms.
        assertEquals(1767294245L + 8 * 3600, stamp.startEpochSeconds(ZoneId.of("UTC")))
    }

    @Test
    fun `an unparseable name yields an unknown stamp`() {
        val stamp = RecordingName.parse("random-clip.mp4")
        assertNull(stamp.startLocal)
        assertNull(stamp.durationSeconds)
    }

    @Test
    fun `ids are stable and path sensitive`() {
        assertEquals(MediaIndex.stableId("a/b.mp4"), MediaIndex.stableId("a/b.mp4"))
        assertEquals(16, MediaIndex.stableId("a/b.mp4").length)
        val distinct = setOf(
            MediaIndex.stableId("a/b.mp4"),
            MediaIndex.stableId("a/c.mp4"),
            MediaIndex.stableId("b/b.mp4")
        )
        assertEquals(3, distinct.size)
    }
}
