package github.rikacelery.cutter.events

import github.rikacelery.cutter.config.CutConfig
import github.rikacelery.cutter.media.Heat
import github.rikacelery.cutter.media.MediaEntry
import github.rikacelery.cutter.media.MediaIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Parses event files once and serves timelines from memory.
 *
 * Raw parsed events are cached (independent of any user offset); the timeline is
 * rebuilt per request, which is cheap because mapping 10k events is a single pass.
 */
class TimelineService(
    private val config: CutConfig,
    private val index: MediaIndex
) {
    private val log = LoggerFactory.getLogger(TimelineService::class.java)
    private val cache = ConcurrentHashMap<String, Cached>()

    private data class Cached(val modifiedAt: Long, val size: Long, val events: List<ParsedEvent>)

    /** Parsed events for a recording, or null when it has no readable event file. */
    suspend fun events(entry: MediaEntry): List<ParsedEvent>? {
        val file = index.resolveEvent(entry) ?: return null
        cache[entry.id]?.let { if (it.modifiedAt == file.lastModified() && it.size == file.length()) return it.events }
        return withContext(Dispatchers.IO) {
            val parsed = runCatching { EventParser.parseFile(file) }
                .onFailure { log.warn("failed to parse {}: {}", file.name, it.message) }
                .getOrNull() ?: return@withContext null
            cache[entry.id] = Cached(file.lastModified(), file.length(), parsed)
            parsed
        }
    }

    /** Timeline for a recording at the given user offset. */
    suspend fun timeline(
        entry: MediaEntry,
        nudgeSeconds: Double = DEFAULT_EVENT_NUDGE_SECONDS
    ): Timeline? {
        val events = events(entry) ?: return null
        return ToyTimeline.build(events, entry.startEpochSeconds, nudgeSeconds)
    }

    /**
     * Heat summary used to rank the library.
     *
     * The counts themselves (commands, tips, chats) do not depend on the offset, so this
     * deliberately uses the same default as [timeline] rather than a special value — a
     * ranking that only held at one nudge would be a bug waiting to happen.
     */
    suspend fun heat(entry: MediaEntry): Heat? {
        if (!entry.hasEvents) return null
        val timeline = timeline(entry) ?: return null
        val stats = timeline.stats
        return Heat(
            toyCommands = stats.toyCommandCount,
            toySeconds = stats.toyBusySeconds,
            specialCommands = stats.specialCommandCount,
            tips = stats.tipCount,
            tipTotal = stats.tipTotal,
            chats = stats.chatCount,
            goalEvents = stats.goalCount,
            showEvents = stats.showCount,
            maxPower = stats.maxPower.ordinalLevel
        )
    }

    /**
     * Fill in heat for every indexed recording that has an event file.
     *
     * Runs as a background pass after a scan and writes results back in batches, so
     * the library ranks by toy activity without the user waiting on ~11k parses.
     */
    suspend fun enrichAll(entries: List<MediaEntry>, concurrency: Int = 4) {
        // Recompute for every recording with events, not just the ones missing heat.
        // The summary is derived from the timeline, so when the timeline's semantics
        // change (as they did when FIFO queueing landed, shifting toy-busy time by
        // +36%) every cached value becomes wrong at once. It costs ~26 s for 11k
        // recordings, which is cheaper than serving stale rankings indefinitely.
        val targets = entries.filter { it.hasEvents }
        if (targets.isEmpty()) {
            log.info("heat enrichment: nothing to do")
            return
        }
        log.info("heat enrichment: {} recordings", targets.size)
        val started = System.currentTimeMillis()
        var done = 0
        targets.chunked(200).forEach { chunk ->
            val updates = HashMap<String, Heat>(chunk.size)
            coroutineScope {
                chunk.map { entry ->
                    async(Dispatchers.IO) {
                        runCatching { heat(entry) }.getOrNull()?.let { entry.id to it }
                    }
                }.awaitAll().filterNotNull().forEach { (id, heat) -> updates[id] = heat }
            }
            index.applyHeat(updates)
            done += chunk.size
            if (done % 1000 < 200) {
                log.info("heat enrichment: {}/{} ({} s)", done, targets.size, (System.currentTimeMillis() - started) / 1000)
            }
        }
        log.info("heat enrichment complete: {} recordings in {} s", done, (System.currentTimeMillis() - started) / 1000)
    }

    fun invalidate(id: String) {
        cache.remove(id)
    }
}
