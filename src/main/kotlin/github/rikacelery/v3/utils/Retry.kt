package github.rikacelery.v3.utils

import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private fun isBusiness4xx(e: Throwable): Boolean =
    e is ClientRequestException && e.response.status.value in 400..499

/**
 * Retries [function] up to [i] times, passing the 0-based attempt index.
 *
 * `CancellationException` is never retried or swallowed: a cancelled coroutine must stop at once,
 * and treating cancellation as a retryable failure both delays shutdown and hides the cancel.
 */
suspend fun <T> withRetry(i: Int, stopIf: (Throwable) -> Boolean = { isBusiness4xx(it) }, function: suspend (n: Int) -> T): T {
    require(i > 0) { "withRetry requires at least one attempt, got $i" }
    var err: Throwable? = null
    for (j in 0 until i) {
        try {
            return function(j) // pass the attempt index, not the total count
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (stopIf(e)) throw e
            err = e
            if (j < i - 1) delay(1000) // no pointless sleep after the final attempt
        }
    }
    throw err ?: IllegalStateException("withRetry exhausted without attempting")
}

suspend fun <T> withRetryOrNull(i: Int, stopIf: (Throwable) -> Boolean = { isBusiness4xx(it) }, function: suspend () -> T): T? {
    require(i > 0) { "withRetryOrNull requires at least one attempt, got $i" }
    for (j in 0 until i) {
        try {
            return function()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (stopIf(e)) return null
            if (j < i - 1) delay(1000)
        }
    }
    return null
}
