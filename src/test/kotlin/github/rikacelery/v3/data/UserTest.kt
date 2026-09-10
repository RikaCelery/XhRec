package github.rikacelery.v3.data

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserTest {

    @Test
    fun `toString never reveals the account cookie`() {
        val user = User(cookie = "csrftoken=super-secret-session", userId = 42L, username = "tester", coins = 100L)

        val text = user.toString()

        assertFalse(text.contains("super-secret-session"), "the cookie must never reach a log line: $text")
        assertFalse(text.contains("csrftoken"), "the cookie must never reach a log line: $text")
        assertTrue(text.contains("42"), text)
        assertTrue(text.contains("tester"), text)
    }
}
