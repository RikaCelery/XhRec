package github.rikacelery.v3.utils

import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

object SensitiveStringRegistry {
    @Volatile var enabled: Boolean = true
    private val randomSuffix: String = Integer.toHexString((Math.random() * 0x10000).toInt()).padStart(4, '0')
    private val mapping = ConcurrentHashMap<String, String>()

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

    fun mask(original: String): String {
        mapping[original]?.let { return it }
        val crc = CRC32()
        crc.update(original.toByteArray(Charsets.UTF_8))
        crc.update(randomSuffix.toByteArray(Charsets.UTF_8))
        val masked = "%08x".format(crc.value)
        val winner = mapping.putIfAbsent(original, masked)
        if (winner != null) return winner
        sortedEntries = mapping.entries.map { it.key to it.value }.sortedByDescending { it.first.length }
        return masked
    }

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
