package github.rikacelery.v3.utils

import org.junit.jupiter.api.Test
import java.time.ZonedDateTime
import java.time.ZoneId
import kotlin.test.*

class ExportVerifyTest {
    @Test
    fun cdnSelector_export_has_no_type_error() {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.of(2024, 1, 15, 10, 30, 0, 0, zone).toInstant().toEpochMilli()
        CdnSelector.reset()
        // Record data (many slots, triggers EWMA array serialization)
        repeat(5) {
            CdnSelector.record("cdn-a.com", 200, now = now)
            CdnSelector.record("cdn-b.com", 500, now = now)
        }
        CdnSelector.recordFailure("cdn-a.com", now)
        // Export must not throw
        val json = CdnSelector.exportState()
        assertTrue(json.isNotEmpty())
        assertTrue(json.contains("slotEwma"))
        assertTrue(json.contains("-1.0"), "NaN should be exported as the -1.0 sentinel")
        println("EXPORT_OK, json length: " + json.length)
    }

    @Test
    fun modelSchedule_export_no_error() {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.of(2024, 1, 15, 10, 30, 0, 0, zone).toInstant().toEpochMilli()
        ModelSchedule.reset()
        repeat(5) { ModelSchedule.record(1L, now) }
        val json = ModelSchedule.exportState()
        assertTrue(json.isNotEmpty())
        println("MODEL_EXPORT_OK, json length: " + json.length)
    }
}
