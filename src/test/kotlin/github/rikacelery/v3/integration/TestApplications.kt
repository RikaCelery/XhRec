package github.rikacelery.v3.integration

import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.test.dispatcher.runTestWithRealTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Wall-clock budget for a test that drives real pipelines over real sockets.
 *
 * Generous on purpose: a test that genuinely hangs should fail on its own awaited condition,
 * which names what it was waiting for, rather than on a stopwatch that reports nothing.
 */
val TEST_BUDGET: Duration = 3.minutes

/**
 * `testApplication` with a budget we control.
 *
 * Ktor's own entry point gives every test a **60 s** budget and offers no way to change it:
 * `testApplication` forwards to `runTestWithRealTime` with no timeout argument, and that
 * function's default is the hardcoded `60_000L` — visible as `ldc2_w 60000l` in
 * `io.ktor.test.dispatcher.TestJvmKt`. Wrapping it in `runTest(timeout = …)` or
 * `runBlocking { withTimeout(…) }` does not help (measured: still cancelled at ~60 020 ms),
 * because the cap belongs to that inner scope.
 *
 * These tests finish well inside 60 s on an idle machine and do not when it is loaded — which is
 * a property of the environment rather than of the code under test: the same test passes 10/10
 * in isolation and fails intermittently in a loaded full-suite run.
 *
 * The body is Ktor's own `runTestApplication`: build the builder, run the block (which installs
 * routes and issues requests), then start the application. Touching the client builds the
 * application, so configuration has to happen before the start.
 */
fun testApplicationWithBudget(
    timeout: Duration = TEST_BUDGET,
    block: suspend ApplicationTestBuilder.() -> Unit
) = runTestWithRealTime(timeout = timeout) {
    val app = ApplicationTestBuilder()
    app.block()
    app.startApplication() // returns once the engine is running, like Ktor's own wrapper
}
