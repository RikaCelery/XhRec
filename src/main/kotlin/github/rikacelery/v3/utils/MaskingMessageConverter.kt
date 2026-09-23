package github.rikacelery.v3.utils

import ch.qos.logback.classic.pattern.MessageConverter
import ch.qos.logback.classic.spi.ILoggingEvent

class MaskingMessageConverter : MessageConverter() {

    override fun convert(event: ILoggingEvent): String {
        val msg = event.formattedMessage ?: return ""
        return mask(msg)
    }

    companion object {
        /**
         * The credential patterns on their own: token, cookie, key and proxy values.
         *
         * Shared with anything that renders internal state outside the log pipeline —
         * `GET /diagnose` embeds state machine transition data verbatim, and that data carries
         * playlist URLs. Unlike [mask] this leaves room ids alone: those are masked in logs because
         * a log line travels, while the diagnose view already reports the room it is about.
         */
        fun maskCredentials(text: String): String {
            if (!SensitiveStringRegistry.enabled) return text
            return STATIC_RULES.fold(text) { acc, rule -> rule.first.replace(acc, rule.second) }
        }

        /** The full log masking pipeline: credentials, then room ids and the URLs that carry them. */
        fun mask(text: String): String {
            if (!SensitiveStringRegistry.enabled) return text
            var msg = maskCredentials(text)
            // Room ids are masked before the substring pass and only here: an id is pure digits, so
            // maskText must never hold it (it would rewrite every unrelated number). The replacement
            // is the room's own name mask, which keeps an id and its model name readable as one entity.
            msg = ROOM_ID_RULE.replace(msg) { m ->
                m.groupValues[1] + m.groupValues[2] +
                    SensitiveStringRegistry.maskPattern(m.groupValues[3]) + m.groupValues[4]
            }
            msg = SensitiveStringRegistry.maskText(msg)
            // Room ids also sit in HLS/CDN/API URLs (`/hls/1001/master/1001_auto.m3u8`,
            // `/api/front/v2/broadcasts/1001`, `1001_480p_..._<segid>.mp4`). Replace the numeric path
            // segments/filename prefixes directly; they map to the same mask as the room name.
            msg = URL_RULE.replace(msg) { url ->
                PATH_ID_RULE.replace(url.value) { m -> SensitiveStringRegistry.maskPattern(m.value) }
            }
            return msg
        }

        /**
         * Masks the id segments of a *bare* path, i.e. one that is not wrapped in a URL.
         *
         * [mask] applies [PATH_ID_RULE] only inside a full `https://…` match, so a path logged on
         * its own (`api/front/v2/models/1001/cam`) would carry a raw room id into a log that masks
         * every other one. Anything that logs a bare path routes it through here.
         */
        fun maskPath(path: String): String =
            if (!SensitiveStringRegistry.enabled) path
            else PATH_ID_RULE.replace(path) { m -> SensitiveStringRegistry.maskPattern(m.value) }

        private val STATIC_RULES: List<Pair<Regex, String>> = listOf(
            // JWT token
            Regex("eyJ[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+") to "***jwt***",
            // Cookie header value (case-insensitive: Cookie: xxx)
            Regex("[Cc]ookie:\\s*[^\\s,;()]+") to "Cookie: ***",
            // aclAuth URL parameter
            Regex("aclAuth=[^&\\s]+") to "aclAuth=***",
            // pkey URL parameter value
            Regex("pkey=[^&\\s]+") to "pkey=***",
            // HTTP proxy address
            Regex("proxy=https?://[^\\s]+") to "proxy=***",
        )

        /** Covers `roomId=1001`, `roomId="1001"`, `"roomId":1001` and `roomId: 1001`. */
        private val ROOM_ID_RULE = Regex("""(["']?roomId["']?\s*[=:]\s*)(["']?)(\d+)(["']?)""")

        /** An absolute http(s) URL, greedily up to the first whitespace. */
        private val URL_RULE = Regex("""https?://\S+""")

        /**
         * A 3+ digit run that is a whole path segment (`/hls/1001/master`), or the start of a file
         * name (`/1001_480p_h264_...mp4`). Two digits or fewer are left alone so versions (`v2`) and
         * resolutions are not touched; segment ids and timestamps are not path segments.
         */
        private val PATH_ID_RULE = Regex("""(?<=/)(\d{3,})(?=[/?_.]|$)""")
    }
}
