package github.rikacelery.v3.data

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
data class Room(
    val id: Long,
    val name: String,
    val quality: String,
    @Serializable(with = DurationMillisSerializer::class)
    val timeLimit: Duration = Duration.INFINITE,
    val sizeLimitBytes: Long,
    val autoPayTicket: Boolean = false,
    val autoPaySpy: Boolean = false,
    val lastSeen: String?,
    val status: String = "",
    val pkey: String = ""
)

object DurationMillisSerializer : KSerializer<Duration> {
    override val descriptor = PrimitiveSerialDescriptor("DurationMillis", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Duration) {
        if (value == Duration.INFINITE) {
            encoder.encodeLong(0)
        } else {
            encoder.encodeLong(value.inWholeMilliseconds)
        }
    }

    override fun deserialize(decoder: Decoder): Duration {
        val millis = decoder.decodeLong()
        return if (millis == 0L) Duration.INFINITE else millis.milliseconds
    }
}

object SizeStrSerializer : KSerializer<Long> {
    override val descriptor = PrimitiveSerialDescriptor("SizeStr", PrimitiveKind.STRING)

    private val binaryUnits = listOf(
        "Ti" to (1L shl 40), "Gi" to (1L shl 30), "Mi" to (1L shl 20), "Ki" to (1L shl 10), "Bi" to 1L
    )
    private val decimalUnits = listOf(
        "T" to 1_000_000_000_000L, "G" to 1_000_000_000L,
        "M" to 1_000_000L, "K" to 1_000L, "B" to 1L
    )

    private val unitMap: Map<String, Long> = buildMap {
        binaryUnits.forEach { (k, v) -> put(k, v); put(k.first().toString(), v) }
        decimalUnits.forEach { (k, v) -> putIfAbsent(k, v) }
    }

    fun parseSizeString(input: String): Long {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed == "0") return 0L
        // Pure number = bytes
        trimmed.toLongOrNull()?.let { return it }
        // Compound like "1G200M" or single like "100Mi"
        val upper = trimmed.uppercase()
        val keys = unitMap.keys.sortedByDescending { it.length }
        var total = 0L
        var numberBuf = ""
        var i = 0
        while (i < upper.length) {
            val c = upper[i]
            when {
                c.isDigit() || c == '.' -> { numberBuf += c; i++ }
                c.isWhitespace() -> i++
                c.isLetter() -> {
                    val matched = keys.firstOrNull { upper.startsWith(it, i) }
                        ?: throw IllegalArgumentException("Unknown unit at '$c' in '$input'")
                    if (numberBuf.isEmpty()) throw IllegalArgumentException("Missing number before '$matched' in '$input'")
                    total += (numberBuf.toDouble() * unitMap[matched]!!).toLong()
                    numberBuf = ""
                    i += matched.length
                }
                else -> throw IllegalArgumentException("Invalid character '$c' in '$input'")
            }
        }
        if (numberBuf.isNotEmpty()) throw IllegalArgumentException("Trailing number without unit: $numberBuf")
        return total
    }

    override fun serialize(encoder: Encoder, value: Long): Unit {
        val s = when {
            value >= 1024L*1024*1024*1024 -> "${value / (1024L*1024*1024*1024)}Ti"
            value >= 1024*1024*1024 -> "${value / (1024*1024*1024)}Gi"
            value >= 1024*1024 -> "${value / (1024*1024)}Mi"
            value >= 1024 -> "${value / 1024}Ki"
            else -> "${value}Bi"
        }
        encoder.encodeString(s)
    }
    override fun deserialize(decoder: Decoder): Long = parseSizeString(decoder.decodeString())
}
