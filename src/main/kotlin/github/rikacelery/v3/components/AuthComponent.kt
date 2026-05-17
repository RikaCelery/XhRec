package github.rikacelery.v3.components

import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.data.User
import github.rikacelery.v3.events.*
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap

sealed interface AuthMsg
data class HandleAuthQuery(val env: CommandEnvelope) : AuthMsg
data class LoadUsers(val users: List<User>) : AuthMsg
data class OnAuthEvent(val event: Any) : AuthMsg

class AuthComponent(
    eventBus: EventBus,
    parentScope: CoroutineScope
) : Actor<AuthMsg>("AuthComponent", eventBus, parentScope) {

    private val users = ConcurrentHashMap<Long, User>()

    override suspend fun onStart(scope: CoroutineScope) {
        subscribe<AuthExpired>(AuthExpired::class)
    }

    override suspend fun wrapEvent(event: Any): AuthMsg? = when (event) {
        is AuthExpired -> OnAuthEvent(event)
        is CommandEnvelope -> HandleAuthQuery(event)
        else -> null
    }

    override suspend fun handle(msg: AuthMsg) = when (msg) {
        is LoadUsers -> { msg.users.forEach { users[it.userId] = it }; logger.info("Loaded ${users.size} users") }
        is OnAuthEvent -> when (msg.event) {
            is AuthExpired -> { users.remove(msg.event.userId); logger.info("User ${msg.event.userId} expired, removed") }
            else -> {}
        }
        is HandleAuthQuery -> handleQuery(msg.env)
    }

    private suspend fun handleQuery(env: CommandEnvelope) {
        val ack = when (env.command) {
            else -> ErrorResponse("unknown auth query")
        }
        eventBus.publish(CommandAck(env.id, ack))
    }

    fun validPaymentAccount(coins: Long): List<User> = users.values.filter { it.coins >= coins }
}
