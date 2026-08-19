package github.rikacelery.v3.utils

import github.rikacelery.v3.data.Hosts
import io.ktor.client.request.get
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.withTimeout

/**
 * Lightweight stream readiness probe: a single fast GET of the master playlist.
 * SchedulerComponent uses it to start recording as soon as the HLS stream is
 * distributing, without round-tripping through the session mailbox (which would
 * serialize probes and block other session work).
 */
object StreamProbe {
    suspend fun masterReady(roomId: Long, pkey: String): Boolean {
        val client = ClientManager.getProxiedClient("probe", http1 = false)
        val url = buildUrl {
            protocol = URLProtocol.HTTPS
            host = Hosts.current.hlsMasterHost
            encodedPath = "/hls/" + roomId + "/master/" + roomId + "_auto.m3u8"
            parameters["psch"] = "v2"
            parameters["pkey"] = pkey
        }.toString()
        return try {
            val response = withTimeout(3_000) { client.get(url) }
            response.status.value in 200..299
        } catch (e: Exception) {
            false
        }
    }
}
