package github.rikacelery.cutter.web

import github.rikacelery.cutter.media.Heat
import kotlinx.serialization.Serializable

@Serializable
data class HealthDto(
    val status: String,
    val version: String,
    val ffmpeg: String?,
    val ffprobe: String?,
    /**
     * How many media roots are indexed.
     *
     * The paths themselves are the server's business: they identify its filesystem to a
     * browser, which cannot use them, and they were only ever here because they were
     * convenient to log.
     */
    val mediaRootCount: Int,
    val zone: String,
    val scanning: Boolean,
    val generation: Long,
    val lastScanAt: Long,
    val lastScanMillis: Long,
    val indexed: Int
)

@Serializable
data class LibraryItemDto(
    val id: String,
    val relPath: String,
    val fileName: String,
    val room: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val durationSeconds: Double?,
    val startEpochSeconds: Long?,
    val hasEvents: Boolean,
    val heat: Heat? = null
)

@Serializable
data class LibraryPageDto(
    val generation: Long,
    val total: Int,
    val offset: Int,
    val items: List<LibraryItemDto>,
    val rooms: List<RoomFacetDto>,
    /** True while the background enrichment pass is still filling in heat. */
    val enriching: Boolean = false
)

@Serializable
data class RoomFacetDto(val room: String, val count: Int)

@Serializable
data class ErrorDto(val error: String, val detail: String? = null)

@Serializable
data class ScanResultDto(val indexed: Int, val generation: Long, val elapsedMs: Long)

// ---------------------------------------------------------------- events / lanes

@Serializable
data class EventDto(
    val t: Double?,
    val kind: String,
    val confidence: String,
    val user: String? = null,
    val text: String? = null,
    val amount: Long? = null,
    val source: String? = null,
    val trigger: String? = null,
    val action: String? = null,
    val seconds: Double? = null,
    val power: Int = 0,
    val goal: Long? = null,
    val spent: Long? = null,
    val goalText: String? = null
)

@Serializable
data class ToyIntervalDto(
    val start: Double,
    val end: Double,
    val power: Int,
    val action: String? = null,
    val user: String? = null,
    val amount: Long? = null
)

@Serializable
data class GiftDto(val t: Double, val amount: Long, val source: String? = null, val user: String? = null)

@Serializable
data class SpecialDto(val t: Double, val action: String, val user: String? = null, val amount: Long? = null)

@Serializable
data class GoalPointDto(val t: Double, val goal: Long?, val spent: Long?, val text: String?)

@Serializable
data class StatsDto(
    val toyCommandCount: Int,
    val toySecondsSum: Double,
    val toyBusySeconds: Double,
    val specialCommandCount: Int,
    val tipCount: Int,
    val tipTotal: Long,
    val chatCount: Int,
    val goalCount: Int,
    val showCount: Int,
    val maxPower: Int,
    val withOwnTimestamp: Int,
    val confidence: String
)

@Serializable
data class LanesDto(
    val mediaId: String,
    val durationSeconds: Double?,
    val nudgeSeconds: Double,
    val toyIntervals: List<ToyIntervalDto>,
    /** Truthful intensity curve; see [github.rikacelery.cutter.events.Timeline.levelSegments]. */
    val toyLevels: List<ToyIntervalDto>,
    val toySpecials: List<SpecialDto>,
    val gifts: List<GiftDto>,
    val goals: List<GoalPointDto>,
    val chats: List<Double>,
    val events: List<EventDto>,
    val stats: StatsDto
)

// ---------------------------------------------------------------- project / export

@Serializable
data class SegmentDto(
    val start: Double,
    val end: Double? = null,
    val name: String = "",
    val tags: Map<String, String> = emptyMap(),
    val selected: Boolean = true
)

@Serializable
data class ProjectDto(
    val mediaId: String,
    val mediaFileName: String,
    val segments: List<SegmentDto>,
    val warning: String? = null
)

@Serializable
data class ExportItemDto(
    val index: Int,
    val sourceName: String,
    val requestedStart: Double,
    val requestedEnd: Double,
    val actualStart: Double,
    val snapDelta: Double,
    val outputName: String,
    val state: String,
    val error: String? = null,
    val bytes: Long = 0
)

@Serializable
data class ExportJobDto(
    val id: String,
    val state: String,
    val outputDir: String,
    val total: Int,
    val completed: Int,
    val failed: Int,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val items: List<ExportItemDto>,
    val log: List<String>
)

@Serializable
data class KeyframeDto(val from: Double, val to: Double, val keyframes: List<Double>)

@Serializable
data class PlannedCutDto(
    val requestedStart: Double,
    val actualStart: Double,
    val snapDelta: Double,
    val outputName: String,
    val keyframes: List<Double>
)

@Serializable
data class ExportRequestItemDto(val mediaId: String, val segments: List<SegmentDto>)

@Serializable
data class ExportRequestDto(
    val items: List<ExportRequestItemDto>,
    val outputDir: String? = null,
    val snap: String? = null
)

@Serializable
data class CancelDto(val cancelled: Boolean)

@Serializable
data class CachedFramesDto(val width: Int, val times: List<Double>)

/**
 * One rung of the timeline's frame pyramid.
 *
 * The client subdivides the timeline by halving, and at every level it asks for the
 * timestamps on that level's grid. The server answers with the times it actually
 * used ([times], always `index * step` snapped to the frame cache grid) so the client
 * never has to reproduce the rounding and end up with a near miss next to a hit.
 */
@Serializable
data class FrameStripDto(
    val width: Int,
    val step: Double,
    val times: List<Double>
)

@Serializable
data class ProxyDto(
    val mediaId: String,
    val status: String,
    val height: Int,
    val progress: Double = 0.0,
    val bytes: Long = 0,
    val error: String? = null
)
