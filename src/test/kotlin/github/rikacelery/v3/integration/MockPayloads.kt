package github.rikacelery.v3.integration

import github.rikacelery.v3.crypto.Decrypter
import java.util.Base64

/**
 * Deterministic payload builders shared by the mock platform server and its assertions.
 *
 * Every token produced here must round-trip through the production [Decrypter], so the
 * playlists the mock serves exercise the real M3U8 parsing/decryption path instead of
 * bypassing it. All bytes are a pure function of (roomId, generation, index) so tests can
 * recompute expected output without recording it.
 */
object MockPayloads {
    /** Base64 of "mock-decrypt-key" — the key the fixture hands to `GetDecryptKey`. */
    const val DEFAULT_DECRYPT_KEY = "bW9jay1kZWNyeXB0LWtleQ=="
    /** Name advertised in `#EXT-X-MOUFLON:PSCH:` and used as the decrypt-key id. */
    const val DEFAULT_PSCH_KEY = "key-id"
    const val SEGMENT_SIZE = 512
    const val INIT_SIZE = 128
    const val DEFAULT_WINDOW = 3

    /**
     * Encodes [plain] so production `Decrypter.decode(token.reversed(), key)` returns it.
     * The M3U8 parser reverses the token before decoding, hence the trailing reverse.
     */
    fun encryptToken(plain: String, keyB64: String = DEFAULT_DECRYPT_KEY): String {
        val key = Base64.getDecoder().decode(keyB64)
        val bytes = plain.toByteArray(Charsets.UTF_8)
        val xored = ByteArray(bytes.size) { i ->
            (bytes[i].toInt() xor (key[i % key.size].toInt() and 0xFF)).toByte()
        }
        return Base64.getEncoder().encodeToString(xored).reversed()
    }

    /** Segment ids increase monotonically within and across generations of one room. */
    fun segmentId(generation: Int, index: Int): Long = generation.toLong() * 1_000L + index

    fun initPath(roomId: Long, generation: Int): String = "/media/$roomId/${roomId}_init_$generation.mp4"

    /**
     * Segment path matches production `M3u8Parser.segmentIDFromUrl`:
     * `<roomId>_<segmentId>_<16 alnum>_<10 digits>.mp4`.
     */
    fun segmentPath(roomId: Long, generation: Int, index: Int): String =
        "/media/$roomId/${roomId}_${segmentId(generation, index)}_" +
            "${suffix(generation, index)}_${timestamp(index)}.mp4"

    fun initUrl(baseUrl: String, roomId: Long, generation: Int): String =
        baseUrl.trimEnd('/') + initPath(roomId, generation)

    fun segmentUrl(baseUrl: String, roomId: Long, generation: Int, index: Int): String =
        baseUrl.trimEnd('/') + segmentPath(roomId, generation, index)

    fun initBytes(roomId: Long, generation: Int): ByteArray {
        val prefix = "INIT:$roomId:$generation:".toByteArray(Charsets.UTF_8)
        require(prefix.size < INIT_SIZE) { "init prefix too long" }
        return prefix + fill(seed(roomId, generation, -1), INIT_SIZE - prefix.size)
    }

    /** Exactly [SEGMENT_SIZE] bytes, deterministic per (roomId, generation, index). */
    fun segmentBytes(roomId: Long, generation: Int, index: Int): ByteArray {
        val prefix = "SEG:$roomId:$generation:$index:".toByteArray(Charsets.UTF_8)
        require(prefix.size < SEGMENT_SIZE) { "segment prefix too long" }
        return prefix + fill(seed(roomId, generation, index), SEGMENT_SIZE - prefix.size)
    }

    /**
     * Sliding-window media playlist: only segments in `fromIndex..throughIndex` are listed,
     * so consecutive polls overlap and the session's dedupe has to hold.
     */
    fun mediaPlaylistText(
        baseUrl: String,
        roomId: Long,
        generation: Int,
        fromIndex: Int,
        throughIndex: Int,
        keyB64: String = DEFAULT_DECRYPT_KEY
    ): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:7")
        appendLine("#EXT-X-TARGETDURATION:2")
        appendLine("#EXT-X-MEDIA-SEQUENCE:$fromIndex")
        appendLine("#EXT-X-MAP:URI=\"${initUrl(baseUrl, roomId, generation)}\"")
        for (index in fromIndex..throughIndex) {
            val token = encryptToken(segmentUrl(baseUrl, roomId, generation, index), keyB64)
            appendLine("#EXT-X-MOUFLON:URI:$token")
        }
    }

    /** Master playlist advertising a single 360p variant plus the PSCH decrypt key name. */
    fun masterPlaylistText(mediaUrl: String, pschKeyName: String = DEFAULT_PSCH_KEY): String =
        masterPlaylistText(listOf(MasterVariant(1_000_000, "640x360", 30, mediaUrl)), pschKeyName)

    /** Master playlist with an arbitrary variant ladder; `parseMaster` derives names from RESOLUTION/FRAME-RATE. */
    fun masterPlaylistText(variants: List<MasterVariant>, pschKeyName: String = DEFAULT_PSCH_KEY): String =
        buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-MOUFLON:PSCH:$pschKeyName")
            variants.forEach { variant ->
                appendLine(
                    "#EXT-X-STREAM-INF:BANDWIDTH=${variant.bandwidth}," +
                        "RESOLUTION=${variant.resolution},FRAME-RATE=${variant.frameRate}"
                )
                appendLine(variant.url)
            }
        }

    /** One master-playlist variant entry. */
    data class MasterVariant(
        val bandwidth: Long,
        val resolution: String,
        val frameRate: Int,
        val url: String
    )

    /** Window bounds for a room that has published [available] segments. */
    fun window(available: Int, windowSize: Int = DEFAULT_WINDOW): IntRange {
        if (available <= 0) return IntRange.EMPTY
        return (available - windowSize + 1).coerceAtLeast(1)..available
    }

    private fun suffix(generation: Int, index: Int): String =
        String.format("%016x", seed(0x5EED, generation, index) and 0xFFFFFFFFFFFFL)

    private fun timestamp(index: Int): String = String.format("%010d", 1_700_000_000L + index)

    private fun seed(roomId: Long, generation: Int, index: Int): Long =
        roomId * 1_000_003L + generation * 1_009L + index * 97L

    private fun fill(seed: Long, size: Int): ByteArray {
        var s = seed
        val out = ByteArray(size)
        for (i in out.indices) {
            s = s * 6364136223846793005L + 1442695040888963407L
            out[i] = ((s ushr 33).toInt() and 0xFF).toByte()
        }
        return out
    }

    /** Round-trip helper used by tests and by playlist assertions. */
    fun decodeToken(token: String, keyB64: String = DEFAULT_DECRYPT_KEY): String =
        Decrypter.decode(token.reversed(), keyB64)
}
