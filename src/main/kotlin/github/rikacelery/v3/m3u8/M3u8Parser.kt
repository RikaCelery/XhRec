package github.rikacelery.v3.m3u8

import github.rikacelery.v2.Session.Companion.DECRYPT_KEY_V2
import github.rikacelery.v3.events.Segment
import java.util.NoSuchElementException
import kotlin.text.replace
import kotlin.text.startsWith
import kotlin.text.substringAfterLast

object M3u8Parser {
    private val mouflonRegex = Regex("#EXT-X-MOUFLON:URI=\"([^\"]+)\"")
    private val mapRegex = Regex("#EXT-X-MAP:URI=\"([^\"]+)\"")
    private fun parseInitUrl(lines: List<String>): String {
        return try {
            lines.first { it.startsWith("#EXT-X-MAP") }.substringAfter("#EXT-X-MAP:URI=").removeSurrounding("\"")
        } catch (e: NoSuchElementException) {
            throw IllegalArgumentException("Missing #EXT-X-MAP tag in playlist", e)
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to parse init URL", e)
        }
    }

    suspend fun parse(m3u8Text: String, decryptKey: String): ParsedPlaylist {
        val rawList = m3u8Text.lines()
        val segments = mutableListOf<Segment>()

        val newList = mutableListOf<String>()
        val initUrl: String = parseInitUrl(rawList)
        for (idx in rawList.indices) {
            if (rawList[idx].startsWith("#EXT-X-MOUFLON:URI:")) {
                val mouflon = rawList[idx].substringAfterLast("#EXT-X-MOUFLON:URI:")
                val encrypted =
                    mouflon.replace("(_part\\d)?\\.mp4".toRegex(), "")
                        .substringBeforeLast("_")
                        .substringAfterLast("_")

                val decrypted = try {
                    val result = runCatching {
                        github.rikacelery.v2.Decrypter.decodev3(
                            encrypted.reversed(),
                            decryptKey
                        )
                    }
                    result.getOrThrow()
                } catch (e: Exception) {
                    throw e
                }
                val dec = rawList[idx].substringAfterLast("#EXT-X-MOUFLON:URI:").replace(encrypted, decrypted)
                newList.add(dec)
            } else {
//                newList.add(rawList[idx])
            }
        }
        segments.addAll(newList.map {
            Segment(it,segmentIDFromUrl(it)?:0)
        })
        return ParsedPlaylist(initUrl, segments)
    }


    private fun segmentIDFromUrl(url: String): Int? {
//        roomid_480p_h265_SEGMENTID_XXXXXXXXXXX_timestamp.mp4 transcended stream
//        roomid_SEGMENTID_XXXXXXXXXXX_timestamp.mp4 raw stream
        val parts = url.substringAfterLast("/").split("_")
        if (parts.size == 6)
            return parts[3].toIntOrNull()
        else if (parts.size == 4)
            return parts[1].toIntOrNull()
        return null
    }
}

data class ParsedPlaylist(
    val initUrl: String?,
    val segments: List<Segment>
)
