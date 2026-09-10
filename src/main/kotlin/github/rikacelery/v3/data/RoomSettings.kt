package github.rikacelery.v3.data

import kotlin.time.Duration
import kotlinx.serialization.Serializable

/**
 * Per-room recording settings: how to record (quality, limits) and what may be recorded
 * (the paid-show switches). Bundled into one value so list.conf, the room commands, the
 * scheduler entry and the WebUI carry a single object instead of a growing parameter list.
 */
@Serializable
data class RoomSettings(
    val quality: String = "highest",
    @Serializable(with = DurationMillisSerializer::class)
    val timeLimit: Duration = Duration.INFINITE,
    val sizeLimitBytes: Long = 0L,
    /** Record public (free) shows. */
    val recordPublic: Boolean = true,
    /** Treat a free-spy private show as worth recording; paid spy still needs [autoPaySpy]. */
    val recordFreeSpy: Boolean = true,
    val autoPayTicket: Boolean = false,
    val autoPaySpy: Boolean = false,
    val pkey: String = "",
)
