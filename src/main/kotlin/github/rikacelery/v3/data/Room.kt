package github.rikacelery.v3.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

data class Room(
    val id: Long,
    val name: String,
    val quality: String,
    val timeLimitMs: Long,
    val sizeLimitBytes: Long,
    val autoPay: Boolean,
    val lastSeen: String?,
    val status: String = ""
)

object DurationMillisSerializer : KSerializer<Long> {
    override val descriptor = PrimitiveSerialDescriptor("DurationMillis", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
    override fun deserialize(decoder: Decoder): Long {
        val v = decoder.decodeLong()
        return if (v == 0L) Long.MAX_VALUE else v * 1000 // 0 = infinite, store as ms
    }
}

object SizeStrSerializer : KSerializer<Long> {
    override val descriptor = PrimitiveSerialDescriptor("SizeStr", PrimitiveKind.STRING)
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
    override fun deserialize(decoder: Decoder): Long = 0L // JSON-only serialization
}
