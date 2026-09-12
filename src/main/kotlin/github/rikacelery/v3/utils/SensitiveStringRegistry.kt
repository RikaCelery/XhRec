package github.rikacelery.v3.utils

import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

object SensitiveStringRegistry {
    @Volatile var enabled: Boolean = true
    private val randomSuffix: String = Integer.toHexString((Math.random() * 0x10000).toInt()).padStart(4, '0')

    /** Strings replaced by [maskText] on every occurrence (model names, usernames). */
    private val mapping = ConcurrentHashMap<String, String>()

    /**
     * Masks for values that are substituted only by a precise pattern, not by [maskText].
     *
     * Room ids live here: they are pure digits, so putting them in [mapping] would make `maskText`
     * replace "1001" inside every unrelated number (segment ids, byte counts, timestamps). They are
     * matched by the `roomId=…` rule in [MaskingMessageConverter] instead, and map to the *same*
     * mask as the room's name so the two stay correlatable in logs.
     */
    private val patternOnly = ConcurrentHashMap<String, String>()

    /**
     * Registered strings, longest first, as a snapshot for [maskText].
     *
     * [maskText] runs for every log event, so the replacement list must not be rebuilt per call:
     * sorting the whole registry on the logging hot path is O(n log n) per line. The snapshot is
     * only re-sorted when a new string is registered. Longest-first matters because a name that is
     * a prefix of another must not partially mask the longer one.
     */
    @Volatile
    private var sortedEntries: List<Pair<String, String>> = emptyList()

    private fun compute(original: String): String {
        val crc = CRC32()
        crc.update(original.toByteArray(Charsets.UTF_8))
        crc.update(randomSuffix.toByteArray(Charsets.UTF_8))
        return "%08x".format(crc.value)
    }

    /** Stable mask of [original], registered for every-occurrence replacement by [maskText]. */
    fun mask(original: String): String {
        mapping[original]?.let { return it }
        val masked = compute(original)
        val winner = mapping.putIfAbsent(original, masked)
        if (winner != null) return winner
        sortedEntries = mapping.entries.map { it.key to it.value }.sortedByDescending { it.first.length }
        return masked
    }

    /**
     * Registers [roomId] to mask to the same value as its [roomName], so a log line that mentions
     * both reads as one entity. Also registers the name itself (as [mask] would).
     */
    fun maskRoom(roomId: Long, roomName: String): String {
        val masked = mask(roomName)
        patternOnly[roomId.toString()] = masked
        return masked
    }

    /**
     * Mask for a value replaced by a pattern rule (room ids). Falls back to a stable per-value mask
     * for ids whose room was never registered, and caches it so repeated logs stay consistent.
     */
    fun maskPattern(original: String): String = patternOnly.getOrPut(original) { compute(original) }

    fun maskText(text: String): String {
        val entries = sortedEntries
        if (entries.isEmpty()) return text
        var result = text
        for ((original, masked) in entries) {
            if (original in result) {
                result = result.replace(original, masked)
            }
        }
        return result
    }
}
