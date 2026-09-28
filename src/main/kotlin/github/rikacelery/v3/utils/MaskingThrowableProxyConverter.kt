package github.rikacelery.v3.utils

import ch.qos.logback.classic.pattern.ThrowableProxyConverter
import ch.qos.logback.classic.spi.IThrowableProxy

/**
 * The stack trace that follows a log line, masked with the same pipeline as the line itself.
 *
 * The message converter only reaches the *message*: logback appends a throwable converter of its own
 * when a pattern names none, and that converter renders the exception's text verbatim. A Ktor
 * failure carries the whole request, so an unmasked stack trace put the signed url (with its `pkey`
 * and `aclAuth`), the room id inside its path and any `Cookie:` header straight into the log —
 * exactly the values [MaskingMessageConverter] exists to hide. Patterns therefore use `%maskedEx`,
 * which is also what stops logback from appending its own.
 */
class MaskingThrowableProxyConverter : ThrowableProxyConverter() {

    protected override fun throwableProxyToString(tp: IThrowableProxy): String =
        MaskingMessageConverter.mask(super.throwableProxyToString(tp))
}
