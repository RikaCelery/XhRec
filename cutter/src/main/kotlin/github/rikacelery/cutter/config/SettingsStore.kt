package github.rikacelery.cutter.config

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Settings that belong to the cutter *instance* rather than to a browser.
 *
 * The editor already keeps per-recording state in `localStorage` (segments, offset,
 * zoom). That is the wrong home for a policy like "delete the source once it has been
 * exported": it decides what happens to files on the NAS, so it has to mean the same
 * thing for every browser and survive a cache wipe of the client.
 *
 * Persisted as JSON next to the index in the cache directory. Writes are atomic
 * (temp file + rename) so a crash mid-save cannot leave a half-written file that
 * silently reverts the setting to its default.
 */
class SettingsStore(private val file: File) {

    private val log = LoggerFactory.getLogger(SettingsStore::class.java)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val mutex = Mutex()

    @Serializable
    data class Settings(
        /**
         * Remove a recording's source file once an export has written out **all** of it.
         *
         * Off by default, and deliberately strict when on: an export that covers only
         * part of a recording never deletes anything. See `ExportQueue` for why.
         */
        val deleteSourceAfterExport: Boolean = false
    )

    @Volatile
    private var current: Settings = Settings()

    /** Last read/write failure, surfaced through `/api/settings` so the UI can warn. */
    @Volatile
    var lastError: String? = null
        private set

    val value: Settings get() = current

    suspend fun load(): Settings = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext current
        runCatching { json.decodeFromString(Settings.serializer(), file.readText()) }
            .onSuccess { current = it; lastError = null }
            .onFailure {
                lastError = "读取设置失败: ${it.message}"
                log.warn("ignoring unreadable settings {}: {}", file.absolutePath, it.message)
            }
        current
    }

    suspend fun update(transform: (Settings) -> Settings): Settings = mutex.withLock {
        val next = transform(current)
        current = next
        withContext(Dispatchers.IO) {
            runCatching { write(next) }
                .onSuccess { lastError = null }
                .onFailure {
                    lastError = "保存设置失败: ${it.message}"
                    log.error("could not persist settings to {}: {}", file.absolutePath, it.message, it)
                }
        }
        next
    }

    private fun write(settings: Settings) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(Settings.serializer(), settings))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
