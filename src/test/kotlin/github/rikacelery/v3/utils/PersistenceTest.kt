package github.rikacelery.v3.utils

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime
import java.time.ZoneId
import kotlin.test.*

class PersistenceTest {

    private val systemZone = ZoneId.systemDefault()
    private val room1 = 1001L
    private val now = ZonedDateTime.of(2024, 1, 15, 10, 30, 0, 0, systemZone)
        .toInstant().toEpochMilli()

    @BeforeEach
    fun setUp() {
        CdnSelector.reset()
        ModelSchedule.reset()
    }

    @Test
    fun cdnSelector_export_import_roundtrip() {
        // Record some data
        repeat(5) {
            CdnSelector.record("cdn-a.com", 200, now = now)
            CdnSelector.record("cdn-b.com", 500, now = now)
        }
        CdnSelector.recordFailure("cdn-a.com", now)

        // Export
        val json = CdnSelector.exportState()
        assertTrue(json.isNotBlank(), "Export should not be blank")
        assertTrue(json.contains("cdn-a.com"), "Export should contain hosts")

        // Reset and import
        CdnSelector.reset()
        CdnSelector.importState(json)

        // Verify data restored
        val snapshot = CdnSelector.snapshot(now)
        val a = snapshot["cdn-a.com"]
        assertNotNull(a, "cdn-a should be restored")
        assertEquals(5, a.totalSuccesses, "Success count should be restored")
        assertEquals(1, a.totalErrors, "Errors should be restored")
        assertTrue(a.estimatedDurationMs > 0, "Duration should be restored")

        val b = snapshot["cdn-b.com"]
        assertNotNull(b, "cdn-b should be restored")
        assertEquals(5, b.totalSuccesses)
    }

    @Test
    fun modelSchedule_export_import_roundtrip() {
        // Record schedule data
        repeat(3) { ModelSchedule.record(room1, now) }
        val later = ZonedDateTime.of(2024, 1, 15, 14, 0, 0, 0, systemZone).toInstant().toEpochMilli()
        ModelSchedule.record(room1, later)

        // Export
        val json = ModelSchedule.exportState()
        assertTrue(json.isNotBlank(), "Export should not be blank")

        // Reset and import
        ModelSchedule.reset()
        ModelSchedule.importState(json)

        // Verify
        val snapshot = ModelSchedule.snapshot(room1)
        assertNotNull(snapshot, "Room should be restored")
        assertEquals(4, snapshot.totalCount, "Total count should be restored")
        assertEquals(10, snapshot.topHours[0].first, "Top hour should be restored")
    }

    @Test
    fun modelSchedule_keeps_hour_distribution() {
        // Record mostly at hour 10, some at hour 14
        repeat(3) { ModelSchedule.record(room1, now) } // 10:30
        val later = ZonedDateTime.of(2024, 1, 15, 14, 0, 0, 0, systemZone).toInstant().toEpochMilli()
        ModelSchedule.record(room1, later) // 14:00

        val json = ModelSchedule.exportState()
        ModelSchedule.reset()
        ModelSchedule.importState(json)

        val dist = ModelSchedule.getDistribution(room1)!!
        assertEquals(3.0 / 4.0, dist[10], 0.01, "Hour 10 should have 75%")
        assertEquals(1.0 / 4.0, dist[14], 0.01, "Hour 14 should have 25%")
    }

    @Test
    fun cdnSelector_software_restart_roundtrip() {
        // Simulate process restart: export is persisted because import/export are stateless
        repeat(10) {
            CdnSelector.record("cdn-a.com", 150, now = now)
            CdnSelector.record("cdn-b.com", 400, now = now)
        }

        val json = CdnSelector.exportState()

        // Simulate fresh start
        CdnSelector.reset()

        // Reload from "disk"
        CdnSelector.importState(json)
        CdnSelector.updateHosts(listOf("cdn-a.com", "cdn-b.com"))

        // Disable exploration for deterministic assertion across 100 picks
        CdnSelector.exploreProbability = 0.0

        // Verify prediction still works
        repeat(100) {
            assertEquals("cdn-a.com", CdnSelector.select(now = now))
        }
    }
}
