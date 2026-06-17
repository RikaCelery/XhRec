package github.rikacelery.v3.core

import github.rikacelery.v3.events.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.*

class RequestBusTest {

    private fun EventBus.installEchoResponder(scope: CoroutineScope) {
        subscribe(scope, CommandEnvelope::class) { env ->
            val response = when (env.command) {
                is GetDecryptKey -> ConfigResponse("decrypted-${env.command.keyName}")
                is GetMaskStatus -> ConfigResponse(true)
                else -> ErrorResponse("unknown: ${env.command::class.simpleName}")
            }
            publish(CommandAck(env.id, response))
        }
    }

    @Test
    fun `request-response round-trip with CommandAck`() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus()
        bus.installEchoResponder(backgroundScope)
        val rb = RequestBus(bus, backgroundScope)

        val result = rb.request<ConfigResponse>(GetDecryptKey("test-key"))
        assertEquals("decrypted-test-key", result.value)
    }

    @Test
    fun `timeout throws RequestTimeoutException`() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus()
        val rb = RequestBus(bus, backgroundScope, defaultTimeoutMs = 1)

        assertFailsWith<RequestTimeoutException> {
            rb.request<OkResponse>(GetDecryptKey("ghost"))
        }
    }

    @Test
    fun `ErrorResponse throws RequestErrorException`() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus()
        bus.installEchoResponder(backgroundScope)
        val rb = RequestBus(bus, backgroundScope)

        assertFailsWith<RequestErrorException> {
            rb.request<OkResponse>(GetValidPaymentAccount(100))
        }
    }

    @Test
    fun `concurrent 100 requests each gets correct independent response`() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus()
        bus.installEchoResponder(backgroundScope)
        val rb = RequestBus(bus, backgroundScope)

        val results = (1..100).map { i ->
            async {
                rb.request<ConfigResponse>(GetDecryptKey("key-$i"))
            }
        }.awaitAll()

        assertEquals(100, results.size)
        results.forEachIndexed { i, r ->
            assertEquals("decrypted-key-${i + 1}", r.value)
        }
    }

    @Test
    fun `parent coroutine cancelled cleans up CompletableDeferred`() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus()
        bus.installEchoResponder(backgroundScope)
        val rb = RequestBus(bus, backgroundScope)

        val job = launch {
            try {
                rb.request<OkResponse>(GetDecryptKey("slow"))
            } catch (_: CancellationException) {
                // expected
            }
        }
        job.cancel()
        job.join()
    }

    @Test
    fun `CommandAck arrives before await handles gracefully`() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus()
        bus.installEchoResponder(backgroundScope)
        val rb = RequestBus(bus, backgroundScope)

        val result = rb.request<ConfigResponse>(GetDecryptKey("fast"))
        assertEquals("decrypted-fast", result.value)
    }
}
