package github.rikacelery.v3.utils

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime
import java.time.ZoneId
import kotlin.test.*

class ScheduleApiVerifyTest {
    @Test
    fun modelSchedule_api_response_buildable() {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.of(2024, 1, 15, 10, 30, 0, 0, zone).toInstant().toEpochMilli()
        ModelSchedule.reset()
        // 记录数据产生分布
        repeat(3) { ModelSchedule.record(55L, now) }
        val later = ZonedDateTime.of(2024, 1, 15, 14, 0, 0, 0, zone).toInstant().toEpochMilli()
        ModelSchedule.record(55L, later)

        val snapshot = ModelSchedule.snapshot(55L)!!
        // 复刻 /model/schedule 的 buildJsonObject 序列化
        val json = buildJsonObject {
            put("roomId", JsonPrimitive(snapshot.roomId))
            put("totalRecordings", JsonPrimitive(snapshot.totalCount))
            put("lastStartTime", JsonPrimitive(snapshot.lastStartTime))
            put("hourDistribution", buildJsonArray { snapshot.hourDistribution.forEach { add(JsonPrimitive((it * 100).toInt())) } })
            put("topHours", buildJsonArray {
                snapshot.topHours.forEach { (h, p) ->
                    add(buildJsonObject { put("hour", JsonPrimitive(h)); put("probability", JsonPrimitive((p * 100).toInt())) })
                }
            })
            put("recentCount", JsonPrimitive(snapshot.recentCount))
        }.toString()

        assertTrue(json.contains("hourDistribution"))
        assertTrue(json.contains("topHours"))
        println("SCHEDULE_RESPONSE_OK: " + json.take(200))
    }

    @Test
    fun modelSchedule_all_response_buildable() {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.of(2024, 1, 15, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        ModelSchedule.reset()
        ModelSchedule.record(1L, now)
        ModelSchedule.record(2L, now)

        val nameById = mapOf(1L to "modelA", 2L to "modelB")
        val roomIds = listOf(1L, 2L)
        val json = buildJsonArray {
            roomIds.forEach { roomId ->
                ModelSchedule.snapshot(roomId)?.let { s ->
                    add(buildJsonObject {
                        put("roomId", JsonPrimitive(s.roomId))
                        put("name", JsonPrimitive(nameById[roomId] ?: ""))
                        put("totalRecordings", JsonPrimitive(s.totalCount))
                        put("topHours", buildJsonArray { s.topHours.take(3).forEach { (h, _) -> add(JsonPrimitive(h)) } })
                    })
                }
            }
        }.toString()
        assertTrue(json.contains("modelA"))
        println("SCHEDULE_ALL_OK: " + json)
    }
}
