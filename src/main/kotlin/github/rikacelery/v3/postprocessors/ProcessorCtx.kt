package github.rikacelery.v3.postprocessors

data class ProcessorCtx(
    val roomId: Long,
    val roomName: String,
    val startTime: Long,
    val endTime: Long,
    val durationMs: Long,
    val quality: String
)
