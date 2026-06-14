package github.rikacelery.v3.utils

import ch.qos.logback.classic.pattern.MessageConverter
import ch.qos.logback.classic.spi.ILoggingEvent

class MaskingMessageConverter : MessageConverter() {

    override fun convert(event: ILoggingEvent): String {
        var msg = event.formattedMessage ?: return ""
        if (!SensitiveStringRegistry.enabled) return msg
        msg = STATIC_RULES.fold(msg) { acc, rule -> rule.first.replace(acc, rule.second) }
        msg = SensitiveStringRegistry.maskText(msg)
        return msg
    }

    companion object {
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
    }
}
