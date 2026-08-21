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
        // 记录数据（包含大量槽位，触发EWMA数组序列化）
        repeat(5) {
            CdnSelector.record("cdn-a.com", 200, now = now)
            CdnSelector.record("cdn-b.com", 500, now = now)
        }
        CdnSelector.recordFailure("cdn-a.com", now)
        // 导出不应抛出异常
        val json = CdnSelector.exportState()
        assertTrue(json.isNotEmpty())
        assertTrue(json.contains("slotEwma"))
        assertTrue(json.contains("-1.0"), "NaN应该导出为-1.0哨兵")
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
