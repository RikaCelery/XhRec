package github.rikacelery.v3.hooks

interface EventHook {
    suspend fun intercept(event: Any): Any?
}
