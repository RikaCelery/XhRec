package github.rikacelery.v3.integration

import github.rikacelery.v3.data.User
import github.rikacelery.v3.events.GetArmedRoomIds
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * WebUI favorites import, driven through the production routes exactly like the dashboard:
 * the accounts are listed, their platform favorites are resolved to room names (already known
 * rooms flagged), the picked ones are imported **disarmed**, and the room list is persisted.
 */
class FavoritesImportIntegrationTest {

    @Test
    fun `imports the picked favorites as disarmed rooms`() = withFixture { fx ->
        fx.mock.addRoom(1001, "model", status = "off")
        fx.mock.addRoom(1002, "favorite-two", status = "off")
        // a live favorite must not start recording by itself
        fx.mock.addRoom(1003, "favorite-three", status = "public")
        fx.mock.setFavorites(42, listOf(1001, 1002, 1003))
        fx.ready(users = listOf(User("cookie-1", 42L, "tester", 100_000L)))
        fx.get("/add?name=model&active=false&quality=720p").expectOk("Room added: model")

        val accountsBody = fx.get("/users").bodyAsText()
        assertFalse(
            accountsBody.contains("cookie-1"),
            "the account cookie must never reach the browser: $accountsBody"
        )
        val accounts = Json.parseToJsonElement(accountsBody).jsonArray
        assertEquals(listOf(42L), accounts.map { it.jsonObject["userId"]!!.jsonPrimitive.long })
        assertEquals("tester", accounts.single().jsonObject["username"]!!.jsonPrimitive.content)

        val candidates = Json.parseToJsonElement(fx.get("/favorites/candidates?users=42").bodyAsText())
            .jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("favorite-three", "favorite-two", "model"),
            candidates.map { it["name"]!!.jsonPrimitive.content }
        )
        assertTrue(
            candidates.single { it["name"]!!.jsonPrimitive.content == "model" }["existing"]!!.jsonPrimitive.boolean,
            "a favorited model that is already a room is flagged instead of offered again"
        )

        // the user picked only the two new favorites
        val imported = fx.postForm("/favorites/import", mapOf("ids" to "1002,1003"))
        assertEquals(HttpStatusCode.OK, imported.status)
        assertTrue(imported.bodyAsText().contains("favorite-two"), imported.bodyAsText())

        assertEquals(
            setOf(1001L, 1002L, 1003L),
            fx.rooms().map { it.id }.toSet(),
            "the picked favorites join the room list"
        )
        assertEquals("720p", fx.rooms().single { it.id == 1001L }.quality, "an existing room keeps its configuration")
        assertEquals(
            emptyList(),
            fx.requestBus.request<List<Long>>(GetArmedRoomIds),
            "an imported favorite is never armed"
        )
        assertTrue(fx.sessions().isEmpty(), "a public favorite must not start recording")

        // imported rooms are persisted disarmed: commented out in list.conf
        val listConf = fx.awaitListConf(15.seconds) { it.contains("favorite-two") }
        val lines = listConf.lines().filter { it.isNotBlank() }
        assertEquals(3, lines.size, listConf)
        assertTrue(lines.all { it.startsWith("#") }, "no imported room may be armed:\n$listConf")
    }

    @Test
    fun `a rejected favorites request offers no candidates instead of failing`() = withFixture { fx ->
        fx.mock.addRoom(1002, "favorite-two", status = "off")
        fx.mock.setFavorites(42, listOf(1002))
        fx.ready(users = listOf(User("cookie-1", 42L, "tester", 100_000L)))
        // a 4xx (expired cookie) is a business answer: it must not be retried, it is reported as "no favorites"
        fx.mock.failNext("/api/front/users/42/favorites", MockFault.NotFound)

        val candidates = fx.get("/favorites/candidates?users=42")
        assertEquals(HttpStatusCode.OK, candidates.status)
        assertEquals("[]", candidates.bodyAsText())
        assertTrue(fx.rooms().isEmpty(), "reading candidates never imports anything")

        // a favorite whose model is gone cannot be imported either
        val imported = fx.postForm("/favorites/import", mapOf("ids" to "999"))
        assertEquals(HttpStatusCode.OK, imported.status)
        assertEquals("No favorites imported", imported.bodyAsText())
        assertTrue(fx.rooms().isEmpty())
    }

    @Test
    fun `import without a selection is rejected`() = withFixture { fx ->
        fx.ready(users = listOf(User("cookie-1", 42L, "tester", 100_000L)))

        assertEquals(HttpStatusCode.BadRequest, fx.get("/favorites/candidates").status)
        assertEquals(HttpStatusCode.BadRequest, fx.postForm("/favorites/import", mapOf("ids" to "")).status)
    }
}
