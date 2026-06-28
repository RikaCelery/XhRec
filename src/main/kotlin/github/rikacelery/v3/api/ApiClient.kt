package github.rikacelery.v3.api

import github.rikacelery.v3.data.User
import github.rikacelery.v3.utils.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

object ApiClient {

    suspend fun getRoomFromUrlOrSlug(path: String): Pair<Long, String> {
        val slug = path.substringAfterLast("/")
        val j = withRetry(3) {
            roomFetchBroadcastInfo(slug).jsonObject
        }
        val id = j.PathSingle("item.modelId").asLong()
        val name = j.PathSingle("item.username").asString()
        return Pair(id, name)
    }

    suspend fun getUserFromCookie(cookie: String): User {
        val client = ClientManager.getProxiedClient("api")
        val response = withRetry(3) {
            client.get("https://poplive.xyz/api/front/v3/config/initial") {
                header("Cookie", cookie)
                expectSuccess = true
            }
        }
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val userData = json.PathSingle("initial.client.user")
        return User(
            cookie = cookie, userId = userData.Long("id"),
            username = userData.String("username"), coins = userData.Long("tokens")
        )
    }

    suspend fun userFetchInitial(user: User): JsonObject {
        val client = ClientManager.getProxiedClient("api")
        val response = withRetry(3) {
            client.get("https://poplive.xyz/api/front/v3/config/initial") {
                header("Cookie", user.cookie)
                expectSuccess = true
            }
        }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    suspend fun roomFetchCamInfo(roomName: String, cookie: String): JsonObject {
        val client = ClientManager.getProxiedClient("api")
        val response = withRetry(3) {
            client.get("https://poplive.xyz/api/front/v2/models/username/$roomName/cam") {
                header("Cookie", cookie)
                expectSuccess = true
            }
        }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    suspend fun roomFetchModelToken(roomName: String, user: User): String? {
        val info = roomFetchCamInfo(roomName, user.cookie)
        return info.PathSingle("cam.modelToken").asString().ifBlank { null }
    }

    suspend fun roomRequestGroupShow(roomId: Long, user: User): Boolean {
        val initial = userFetchInitial(user)
        val client = ClientManager.getProxiedClient("api")
        val response = withRetry(3, stopIf = { false }) {
            client.post("https://poplive.xyz/api/front/show/models/$roomId/groupShows/${user.userId}") {
                header("Cookie", user.cookie)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("csrfToken", initial.PathSingle("initial.client.csrfToken").asString())
                    put("csrfTimestamp", initial.PathSingle("initial.client.csrfTimestamp").asString())
                })
            }
        }
        return response.status.value in 200..299
    }

    suspend fun roomQualities(roomName: String): List<String> {
        val info = roomFetchBroadcastInfo(roomName)
        val presetElem = info.PathSingleOrNull("item.settings.presets") ?: run {
            return emptyList()
        }
        val qualities = presetElem.jsonArray.map { it.jsonPrimitive.content }
            .filterNot { it.endsWith("_blurred") }.toMutableList()
        val fps = info.PathSingle("item.settings.fps").asInt().toString()
        val height = info.PathSingle("item.settings.height").asInt().toString()
        val raw = height + "p" + (if (fps != "30") fps else "")
        if (qualities.contains(raw).not())
            qualities.add(0, raw)
        return qualities
    }

    suspend fun roomFetchBroadcastInfo(roomName: String): JsonObject {
        val client = ClientManager.getProxiedClient("api")
        val response = withRetry(3) {
            client.get("https://poplive.xyz/api/front/v1/broadcasts/$roomName")
        }
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        if (response.status == HttpStatusCode.NotFound) {
            val reason =
                runCatching { Json.Default.parseToJsonElement(response.bodyAsText()).String("description") }.getOrNull()
            if (reason == null) {
                throw IllegalStateException("request api failed")
            }
            when {
                reason.matches("Model has new name: newName=(.*)".toRegex()) -> {
                    val newName = "Model has new name: newName=(.*)".toRegex().find(reason)!!.groupValues[1]
                    throw github.rikacelery.v3.exceptions.RenameException(
                        newName
                    )
                }

                reason == "model already deleted" -> {
                    throw github.rikacelery.v3.exceptions.DeletedException()
                }
            }
        }
        return json
    }
}
