package github.rikacelery.v3.hooks

import java.io.File

interface PostProcessorHook {
    suspend fun beforeProcess(file: File, roomId: Long, startTime: Long, durationMs: Long): File
    suspend fun afterProcess(file: File, roomId: Long, startTime: Long, durationMs: Long): File
}
