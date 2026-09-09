package github.rikacelery.v3.integration

import github.rikacelery.v3.crypto.Decrypter
import github.rikacelery.v3.m3u8.M3u8Parser
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure payload tests: the mock server must produce playlists the production parser
 * accepts and bytes that tests can recompute exactly.
 */
class MockPayloadTest {

    @Test
    fun `encrypted token round-trips through the production decrypter`() {
        val plain = "http://127.0.0.1:18080/media/7/7_1001_0123456789abcdef_1700000001.mp4"
        val token = MockPayloads.encryptToken(plain)

        assertEquals(plain, Decrypter.decode(token.reversed(), MockPayloads.DEFAULT_DECRYPT_KEY))
        assertEquals(plain, MockPayloads.decodeToken(token))
        assertTrue(token != plain)
    }

    @Test
    fun `segment bodies are exactly 512 deterministic bytes and ids increase`() {
        val first = MockPayloads.segmentBytes(roomId = 1, generation = 2, index = 3)
        val again = MockPayloads.segmentBytes(roomId = 1, generation = 2, index = 3)
        val next = MockPayloads.segmentBytes(roomId = 1, generation = 2, index = 4)
        val otherRoom = MockPayloads.segmentBytes(roomId = 2, generation = 2, index = 3)

        assertEquals(MockPayloads.SEGMENT_SIZE, first.size)
        assertContentEquals(first, again)
        assertTrue(!first.contentEquals(next))
        assertTrue(!first.contentEquals(otherRoom))

        assertEquals(MockPayloads.INIT_SIZE, MockPayloads.initBytes(roomId = 1, generation = 2).size)

        assertTrue(MockPayloads.segmentId(2, 1) < MockPayloads.segmentId(2, 2))
        assertTrue(MockPayloads.segmentId(2, 999) < MockPayloads.segmentId(3, 1))
    }

    @Test
    fun `segment urls are parsed back to their ids by production parser`() {
        for (index in 1..5) {
            val url = MockPayloads.segmentUrl("http://127.0.0.1:18080", 7, 2, index)
            assertEquals(
                MockPayloads.segmentId(2, index).toInt(),
                M3u8Parser.segmentIDFromUrl(url),
                "url=$url"
            )
        }
    }

    @Test
    fun `media playlist decrypts to ordered urls and windows overlap`() {
        val base = "http://127.0.0.1:18080"
        val roomId = 7L
        val generation = 2

        val firstWindow = MockPayloads.window(available = 3, windowSize = 3)
        val secondWindow = MockPayloads.window(available = 4, windowSize = 3)
        assertEquals(1..3, firstWindow)
        assertEquals(2..4, secondWindow)
        assertTrue(firstWindow.last in secondWindow)

        val text = MockPayloads.mediaPlaylistText(base, roomId, generation, secondWindow.first, secondWindow.last)
        val parsed = M3u8Parser.parse(text, MockPayloads.DEFAULT_DECRYPT_KEY)

        assertEquals(MockPayloads.initUrl(base, roomId, generation), parsed.initUrl)
        assertEquals(
            (secondWindow.first..secondWindow.last).map { MockPayloads.segmentUrl(base, roomId, generation, it) },
            parsed.segments.map { it.url }
        )
        assertEquals(
            (secondWindow.first..secondWindow.last).map { MockPayloads.segmentId(generation, it).toInt() },
            parsed.segments.map { it.index }
        )
    }

    @Test
    fun `master playlist exposes the psch key and the media variant`() {
        val media = "http://127.0.0.1:18080/hls/7/media/7_auto.m3u8"
        val master = M3u8Parser.parseMaster(MockPayloads.masterPlaylistText(media))

        assertEquals(listOf("key-id"), master.pschKeys.map { it.substringAfter(":") })
        assertEquals(1, master.variants.size)
        assertEquals("360p", master.variants.single().name)
        assertEquals(media, master.variants.single().url)
    }
}
