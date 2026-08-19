package github.rikacelery.v3.api

import github.rikacelery.v3.data.User
import github.rikacelery.v3.exceptions.DeletedException
import github.rikacelery.v3.exceptions.RenameException
import github.rikacelery.v3.utils.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

private val RENAME_REGEX = Regex("Model has new name: newName=(.*)")

/**
 * Parses a 404 response body of the broadcasts API. A missing/unknown description is a
 * generic failure; a rename/deleted description is a business result — both throw.
 * Returns Nothing so the caller can treat 404 as a terminal branch.
 */
internal fun throwBroadcast404(body: String): Nothing {
    val reason = runCatching { Json.Default.parseToJsonElement(body).String("description") }.getOrNull()
        ?: throw IllegalStateException("request api failed")
    when {
        reason.matches(RENAME_REGEX) ->
            throw RenameException(RENAME_REGEX.find(reason)!!.groupValues[1])
        reason == "model already deleted" -> throw DeletedException()
        else -> throw IllegalStateException("request api failed: " + reason)
    }
}

/**
 * Platform API client with multi-domain failover: requests are issued against the
 * current (first available) platform host; on transport failure the host enters a
 * cooldown and the next configured host takes over automatically.
 *
 * The platform client runs with expectSuccess=false and handles HTTP statuses explicitly
 * so that 404-based business results (model renamed / deleted) can be detected.
 */
object ApiClient {
    const val DEFAULT_PLATFORM_HOST = "stripchat.com"

    private val logger = LoggerFactory.getLogger("v3.ApiClient")
    private val failover = HostFailover(listOf(DEFAULT_PLATFORM_HOST))

    /**
     * A 4xx response means the platform understood the request and answered with a
     * business status (bad cookie / stale slug / room not available). It must not be
     * retried against the same host, but for platform API calls it may still make
     * sense to fail over to the next configured host.
     */
    private fun is4xx(e: Throwable): Boolean =
        e is ClientRequestException && e.response.status.value in 400..499

    /** Current ordered platform hosts (first entry = primary). */
    val platformHosts: List<String> get() = failover.hosts

    fun applyHosts(hosts: List<String>) {
        failover.updateHosts(hosts.ifEmpty { listOf(DEFAULT_PLATFORM_HOST) })
        logger.info("Platform hosts updated: {}", failover.hosts)
    }

    private val apiClient by lazy { ClientManager.getProxiedClient("api", http1 = true, expectSuccess = false) }

    private fun apiUrl(host: String, path: String): String {
        val h = host.trim().trimEnd('/')
        require(h.isNotEmpty()) { "platformHost must not be blank" }
        return "https://" + h + "/" + path
    }

    /**
     * Run [block] against the current host.
     *
     * - Domain business results matching [stopIf] (Rename/Deleted) propagate
     *   immediately: another host would return the same answer.
     * - A 4xx response is a business result for the current host: it is not retried
     *   (withRetry stops it), but it *does* fail over to the next host.
     * - Network errors (timeouts etc.) are retried by withRetry first; only after
     *   retries are exhausted do we mark the host failed and switch to the next one.
     */
    private suspend fun <T> withHostFallback(
        stopIf: (Throwable) -> Boolean = { it is RenameException || it is DeletedException },
        block: suspend (host: String) -> T
    ): T {
        var lastErr: Throwable? = null
        val tried = HashSet<String>()
        repeat(failover.hosts.size.coerceAtLeast(1)) {
            val host = failover.currentHost()
                ?: throw IllegalStateException("no platform host configured")
            if (!tried.add(host)) return@repeat
            try {
                val result = block(host)
                failover.markSuccess(host)
                return result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (stopIf(e)) throw e
                lastErr = e
                logger.warn("Platform host {} request failed: {}", host, e.message)
                failover.markFailure(host)
            }
        }
        throw lastErr ?: IllegalStateException("no platform host available")
    }

    private fun ensure2xx(host: String, response: HttpResponse): HttpResponse {
        if (response.status.value !in 200..299) {
            val msg = "HTTP " + response.status.value + " from " + host
            if (response.status.value in 400..499) throw ClientRequestException(response, msg)
            throw IllegalStateException(msg)
        }
        return response
    }

    /**
     * Fetches the per-session guest WebSocket auth JWT from config/initial (no cookie =
     * anonymous guest). The token is freshly minted per session by the server.
     */
    suspend fun fetchGuestWsToken(): String {
        val response = withHostFallback { host ->
            withRetry(3) {
                ensure2xx(host, apiClient.get(apiUrl(host, "api/front/v3/config/initial")))
            }
        }
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        return json.PathSingle("initial.client.websocket.token").asString()
    }

    suspend fun getRoomFromUrlOrSlug(path: String): Pair<Long, String> {
        val slug = path.substringAfterLast("/")
        val j = withRetry(3, stopIf = { it is RenameException || it is DeletedException || is4xx(it) }) {
            roomFetchBroadcastInfo(slug).jsonObject
        }
        val id = j.PathSingle("item.modelId").asLong()
        val name = j.PathSingle("item.username").asString()
        return Pair(id, name)
    }

    suspend fun getUserFromCookie(cookie: String): User {
        val response = withHostFallback { host ->
            withRetry(3) {
                ensure2xx(host, apiClient.get(apiUrl(host, "api/front/v3/config/initial")) {
                    header("Cookie", cookie)
                })
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
        val response = withHostFallback { host ->
            withRetry(3) {
                ensure2xx(host, apiClient.get(apiUrl(host, "api/front/v3/config/initial")) {
                    header("Cookie", user.cookie)
                })
            }
        }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    suspend fun roomFetchCamInfo(roomName: String, cookie: String): JsonObject {
        val response = withHostFallback { host ->
            withRetry(3) {
                ensure2xx(host, apiClient.get(apiUrl(host, "api/front/v2/models/username/" + roomName + "/cam")) {
                    header("Cookie", cookie)
                })
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
        val response = withHostFallback { host ->
            withRetry(3, stopIf = { false }) {
                val r = apiClient.post(apiUrl(host, "api/front/show/models/" + roomId + "/groupShows/" + user.userId)) {
                    header("Cookie", user.cookie)
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {
                        put("csrfToken", initial.PathSingle("initial.client.csrfToken").asString())
                        put("csrfTimestamp", initial.PathSingle("initial.client.csrfTimestamp").asString())
                    })
                }
                // transient server errors should retry/fail over; 4xx are business results
                if (r.status.value >= 500) throw IllegalStateException("HTTP " + r.status.value + " from " + host)
                r
            }
        }
        return response.status.value in 200..299
    }

    // TODO: verify the private-show (spy) endpoint verb/params/idempotency on a real show —
    // modeled on the group-show endpoint, currently unconfirmed. If the response body carries
    // the model token it should be parsed and returned instead of a second cam-info poll.
    suspend fun roomRequestSpyShow(roomId: Long, user: User): Boolean {
        val initial = userFetchInitial(user)
        val response = withHostFallback { host ->
            withRetry(3, stopIf = { false }) {
                val r = apiClient.put(
                    apiUrl(host, "api/front/show/models/" + roomId + "/viewers/" + user.userId + "/spy") +
                        "?source=proposePrivate"
                ) {
                    header("Cookie", user.cookie)
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {
                        put("csrfToken", initial.PathSingle("initial.client.csrfToken").asString())
                        put("csrfTimestamp", initial.PathSingle("initial.client.csrfTimestamp").asString())
                    })
                }
                // transient server errors should retry/fail over; 4xx are business results
                if (r.status.value >= 500) throw IllegalStateException("HTTP " + r.status.value + " from " + host)
                r
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

    /**
     * Fetches broadcast info. Explicitly handles 404: "model renamed" and
     * "model deleted" are business exceptions (no retry / no host failover).
     */
    suspend fun roomFetchBroadcastInfo(roomName: String): JsonObject {
        // Rename/Deleted are domain answers and must not fail over to another host.
        val domainBusiness: (Throwable) -> Boolean = { it is RenameException || it is DeletedException }
        // 4xx should not be retried against the same host; withHostFallback still
        // fails over to the next host after the inner retry loop stops.
        val noRetry: (Throwable) -> Boolean = { domainBusiness(it) || is4xx(it) }
        return withHostFallback(stopIf = domainBusiness) { host ->
            withRetry(3, stopIf = noRetry) {
                val response = apiClient.get(apiUrl(host, "api/front/v1/broadcasts/" + roomName))
                val status = response.status.value
                if (status in 200..299) {
                    Json.parseToJsonElement(response.bodyAsText()).jsonObject
                } else if (status == 404) {
                    throwBroadcast404(response.bodyAsText())
                } else if (status in 400..499) {
                    throw ClientRequestException(response, "HTTP " + status + " from " + host)
                } else {
                    throw IllegalStateException("HTTP " + status + " from " + host)
                }
            }
        }
    }
}
