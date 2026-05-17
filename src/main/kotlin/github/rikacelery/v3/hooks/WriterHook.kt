package github.rikacelery.v3.hooks

import java.io.File

interface WriterHook {
    suspend fun beforeFileOpen(roomId: Long, path: String): String
    suspend fun beforeWrite(roomId: Long, data: ByteArray): ByteArray
    suspend fun afterFileClosed(roomId: Long, file: File)
}
