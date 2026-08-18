package github.rikacelery.v3.data

import kotlinx.serialization.json.*

/**
 * All configurable platform/CDN domains.
 *
 * No backward compatibility: the old single-value fields (platformHost etc.) are gone.
 * Lists are ordered — the first entry is the primary host; the rest serve as fallbacks.
 */
data class HostsConfig(
    val platformHosts: List<String> = DEFAULT.platformHosts,
    val webSocketHosts: List<String> = DEFAULT.webSocketHosts,
    val hlsHosts: List<String> = DEFAULT.hlsHosts,
    val hlsMasterHost: String = DEFAULT.hlsMasterHost,
    val webHost: String = DEFAULT.webHost,
    val previewHost: String = DEFAULT.previewHost,
    val thumbHost: String = DEFAULT.thumbHost
) {
    companion object {
        const val DEFAULT_PLATFORM_HOST = "stripchat.com"
        const val DEFAULT_WS_HOST = "websocket-v6.xhamsterlive.com"
        const val DEFAULT_HLS_HOST = "media-hls.doppiocdn.org"
        const val DEFAULT_HLS_MASTER_HOST = "edge-hls.doppiocdn.org"
        const val DEFAULT_WEB_HOST = "xhamsterlive.com"
        const val DEFAULT_PREVIEW_HOST = "zh.xhamsterlive.com"
        const val DEFAULT_THUMB_HOST = "img.doppiocdn.org"

        val DEFAULT = HostsConfig(
            platformHosts = listOf(DEFAULT_PLATFORM_HOST),
            webSocketHosts = listOf(DEFAULT_WS_HOST),
            hlsHosts = listOf(DEFAULT_HLS_HOST),
            hlsMasterHost = DEFAULT_HLS_MASTER_HOST,
            webHost = DEFAULT_WEB_HOST,
            previewHost = DEFAULT_PREVIEW_HOST,
            thumbHost = DEFAULT_THUMB_HOST
        )

        fun sanitizeList(value: List<String>?): List<String> {
            val cleaned = value?.mapNotNull { it.trim().trimEnd('/').takeIf(String::isNotEmpty) }
                ?.distinct() ?: emptyList()
            return cleaned
        }

        fun sanitize(cfg: HostsConfig): HostsConfig {
            val platformHosts = sanitizeList(cfg.platformHosts).ifEmpty { DEFAULT.platformHosts }
            val webSocketHosts = sanitizeList(cfg.webSocketHosts).ifEmpty { DEFAULT.webSocketHosts }
            val hlsHosts = sanitizeList(cfg.hlsHosts).ifEmpty { DEFAULT.hlsHosts }
            val master = cfg.hlsMasterHost.trim().trimEnd('/')
            val web = cfg.webHost.trim().trimEnd('/')
            val preview = cfg.previewHost.trim().trimEnd('/')
            val thumb = cfg.thumbHost.trim().trimEnd('/')
            return HostsConfig(
                platformHosts = platformHosts,
                webSocketHosts = webSocketHosts,
                hlsHosts = hlsHosts,
                hlsMasterHost = master.ifEmpty { DEFAULT.hlsMasterHost },
                webHost = web.ifEmpty { DEFAULT.webHost },
                previewHost = preview.ifEmpty { DEFAULT.previewHost },
                thumbHost = thumb.ifEmpty { DEFAULT.thumbHost }
            )
        }

        fun fromJson(json: JsonObject): HostsConfig {
            fun strList(key: String): List<String>? = json[key]?.jsonArray?.map { it.jsonPrimitive.content }
            return sanitize(
                HostsConfig(
                    platformHosts = strList("platformHosts") ?: emptyList(),
                    webSocketHosts = strList("webSocketHosts") ?: emptyList(),
                    hlsHosts = strList("hlsHosts") ?: emptyList(),
                    hlsMasterHost = json["hlsMasterHost"]?.jsonPrimitive?.content ?: DEFAULT.hlsMasterHost,
                    webHost = json["webHost"]?.jsonPrimitive?.content ?: DEFAULT.webHost,
                    previewHost = json["previewHost"]?.jsonPrimitive?.content ?: DEFAULT.previewHost,
                    thumbHost = json["thumbHost"]?.jsonPrimitive?.content ?: DEFAULT.thumbHost
                )
            )
        }
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("platformHosts", buildJsonArray { platformHosts.forEach { add(it) } })
        put("webSocketHosts", buildJsonArray { webSocketHosts.forEach { add(it) } })
        put("hlsHosts", buildJsonArray { hlsHosts.forEach { add(it) } })
        put("hlsMasterHost", hlsMasterHost)
        put("webHost", webHost)
        put("previewHost", previewHost)
        put("thumbHost", thumbHost)
    }
}

/** Runtime holder of the active host config (updated by ConfigComponent, read everywhere else). */
object Hosts {
    @Volatile
    var current: HostsConfig = HostsConfig.DEFAULT

    fun primaryPlatformHost(): String =
        current.platformHosts.firstOrNull() ?: HostsConfig.DEFAULT_PLATFORM_HOST
}
