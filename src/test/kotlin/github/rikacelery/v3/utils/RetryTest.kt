package github.rikacelery.v3.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RetryTest {

    @Test
    fun withRetry_passes_attempt_index_not_total_count() = runTest {
        val seen = mutableListOf<Int>()
        val result = withRetry(3, stopIf = { false }) { n ->
            seen += n
            if (n < 2) throw RuntimeException("boom $n")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(listOf(0, 1, 2), seen, "attempt index should be 0-based")
    }

    @Test
    fun withRetry_rethrows_last_error_after_exhaustion() = runTest {
        val err = assertFailsWith<RuntimeException> {
            withRetry(2, stopIf = { false }) { throw RuntimeException("always fails") }
        }
        assertEquals("always fails", err.message)
    }

    @Test
    fun withRetryOrNull_returns_null_after_exhaustion() = runTest {
        var calls = 0
        val result = withRetryOrNull(3, stopIf = { false }) {
            calls++
            throw RuntimeException("nope")
        }
        assertEquals(null, result)
        assertEquals(3, calls)
    }

    @Test
    fun withRetry_propagates_cancellation_without_retrying() = runTest {
        var calls = 0
        assertFailsWith<CancellationException> {
            withRetry(3, stopIf = { false }) {
                calls++
                throw CancellationException("cancelled")
            }
        }
        assertEquals(1, calls, "a cancelled coroutine must stop, not be retried")
    }

    @Test
    fun withRetryOrNull_propagates_cancellation_without_retrying() = runTest {
        var calls = 0
        assertFailsWith<CancellationException> {
            withRetryOrNull(3, stopIf = { false }) {
                calls++
                throw CancellationException("cancelled")
            }
        }
        assertEquals(1, calls)
    }
}
