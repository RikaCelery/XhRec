package github.rikacelery.v3.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun runProcessGetStdout(vararg command: String): String {
    return withContext(Dispatchers.IO) {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        output
    }
}
