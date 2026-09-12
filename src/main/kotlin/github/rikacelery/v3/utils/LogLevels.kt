package github.rikacelery.v3.utils

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import org.slf4j.Logger.ROOT_LOGGER_NAME
import org.slf4j.LoggerFactory

/**
 * Runtime log-level control backing the dashboard's log-level picker.
 *
 * The level is applied to the Logback **root** logger, which is what every `github.rikacelery.*`
 * logger inherits from, so one change covers the whole recorder. The per-logger overrides in
 * `logback.xml` (netty, ktor, jetty) deliberately keep their own level — they are the noisy
 * libraries, and raising the root level to TRACE should not flood the log with their internals.
 *
 * `logback.xml` is scanned for changes, but Logback only reconfigures when the file's timestamp
 * actually changes, so a level set here survives until that file is edited or the process restarts.
 * [ConfigComponent][github.rikacelery.v3.components.ConfigComponent] persists the choice in
 * `xhrec.json`, so it is also restored on the next start.
 */
object LogLevels {

    /** The levels offered to the dashboard, from most to least verbose. */
    val LEVELS = listOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF")

    /** The default when `logback.xml` does not pin a root level and nothing was persisted. */
    const val DEFAULT = "DEBUG"

    private fun context(): LoggerContext? = LoggerFactory.getILoggerFactory() as? LoggerContext

    private fun root(): Logger? = context()?.getLogger(ROOT_LOGGER_NAME)

    /** The root level currently in effect, or `null` when the backend is not Logback. */
    fun current(): String? = root()?.level?.toString()

    /** The level to show when [current] is unavailable. */
    fun currentOrDefault(): String = current() ?: DEFAULT

    /** Canonical upper-case spelling of [level], or `null` when it is not a known level. */
    fun normalize(level: String): String? {
        val canonical = level.trim().uppercase()
        return canonical.takeIf { it in LEVELS }
    }

    /**
     * Applies [level] to the root logger.
     *
     * Returns the canonical level that was applied, or `null` when the level is unknown or Logback
     * is not the active backend (in which case the caller reports the failure instead of pretending
     * the change took effect).
     */
    fun apply(level: String): String? {
        val logger = root() ?: return null
        val canonical = normalize(level) ?: return null
        // Level.valueOf is safe here because normalize() already restricted the input to LEVELS.
        logger.level = Level.valueOf(canonical)
        return canonical
    }
}
