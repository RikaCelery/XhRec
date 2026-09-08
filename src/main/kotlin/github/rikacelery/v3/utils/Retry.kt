package github.rikacelery.v3.utils

import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.delay

private fun isBusiness4xx(e: Throwable): Boolean =
    e is ClientRequestException && e.response.status.value in 400..499

suspend fun <T> withRetry(i: Int, stopIf: (Throwable) -> Boolean = { isBusiness4xx(it) }, function: suspend (n:Int) -> T): T {
    var err: Throwable? = null
    (0 until i).forEach { j ->
        runCatching {
            return function(j) // pass the attempt index, not the total count
        }.onFailure {
            if (stopIf(it)) {
                throw it
            }
            err = it
            if (j < i - 1) delay(1000) // no pointless sleep after the final attempt
        }
    }
    throw err!!
}

suspend fun <T> withRetryOrNull(i: Int, stopIf: (Throwable) -> Boolean = { isBusiness4xx(it) }, function: suspend () -> T): T? {
    (0 until i).forEach { j ->
        runCatching {
            return function()
        }.onFailure {
            if (stopIf(it)) {
                return null
            }
            if (j < i - 1) delay(1000)
        }
    }
    return null
}
