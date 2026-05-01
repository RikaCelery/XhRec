package github.rikacelery

import java.util.*

object UserManager {
    val users = Hashtable<Long, User>()
    fun update(vararg users: User) {
        for (user in users) {
            this.users[user.userId] = user
        }
    }
    fun remove(userid: Long): User? {
        return users.remove(userid)
    }
    fun randAccount(): User? {
        return users.values.randomOrNull()
    }
    fun validPaymentAccount(coins: Int): User? {
        for (user in users.values){
            if (user.coins>coins){
                return user
            }
        }
        return null
    }
}