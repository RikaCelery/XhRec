package github.rikacelery.v3.data

data class User(
    val cookie: String,
    val userId: Long,
    val username: String,
    val coins: Long
) {
    /** Never render the cookie: [User] values reach log lines, commands and error messages. */
    override fun toString(): String = "User(userId=$userId, username=$username, coins=$coins)"
}
