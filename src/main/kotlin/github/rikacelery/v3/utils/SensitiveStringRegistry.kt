package github.rikacelery.v3.utils

import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

object SensitiveStringRegistry {
    @Volatile var enabled: Boolean = true
    private val randomSuffix: String = Integer.toHexString((Math.random() * 0x10000).toInt()).padStart(4, '0')
    private val mapping = ConcurrentHashMap<String, String>()

    fun mask(original: String): String {
        return mapping.getOrPut(original) {
            val crc = CRC32()
            crc.update(original.toByteArray(Charsets.UTF_8))
            "%08x%s".format(crc.value, randomSuffix)
        }
    }

    fun maskText(text: String): String {
        var result = text
        mapping.entries
            .sortedByDescending { it.key.length }
            .forEach { (original, masked) ->
                if (original in result) {
                    result = result.replace(original, masked)
                }
            }
        return result
    }
}
