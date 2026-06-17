package github.rikacelery.v3.core

import github.rikacelery.v3.data.*
import github.rikacelery.v3.events.CutPoint
import github.rikacelery.v3.events.EndReason
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrderedEmitterTest {

    private val now = Instant.now()

    private fun success(room: Long, idx: Int) = DownloadResult.Success(
        byteArrayOf(idx.toByte()),
        DownloadMeta("http://x/$idx", 100, false, now)
    )

    private fun failed(idx: Int, room: Long) = DownloadResult.Failed(idx, "http://x/$idx", "404")

    private fun cutPoint(room: Long, reason: EndReason = EndReason.UserStop) =
        DownloadResult.CutPoint(CutPoint(room, 0, "room$room", now, reason, "highest"))

    @Test
    fun `in-order completion emits in-order`() = runTest(UnconfinedTestDispatcher()) {
        val emitted = mutableListOf<DataChannelMsg>()
        val emitter = OrderedEmitter(42) { emitted.add(it) }

        emitter.complete(0, success(42, 0))
        emitter.complete(1, success(42, 1))
        emitter.complete(2, success(42, 2))

        assertEquals(3, emitted.size)
        val d0 = emitted[0] as StreamData
        val d1 = emitted[1] as StreamData
        val d2 = emitted[2] as StreamData
        assertEquals(0, d0.segmentIndex)
        assertEquals(1, d1.segmentIndex)
        assertEquals(2, d2.segmentIndex)
    }

    @Test
    fun `out-of-order completion emits sorted by seq`() = runTest(UnconfinedTestDispatcher()) {
        val emitted = mutableListOf<DataChannelMsg>()
        val emitter = OrderedEmitter(42) { emitted.add(it) }

        emitter.complete(2, success(42, 2))
        emitter.complete(0, success(42, 0))
        // idx=1 not arrived yet, so only 0 should be emitted
        assertEquals(1, emitted.size)
        assertEquals(0, (emitted[0] as StreamData).segmentIndex)

        emitter.complete(1, success(42, 1))
        // now 1 and 2 should drain
        assertEquals(3, emitted.size)
        assertEquals(1, (emitted[1] as StreamData).segmentIndex)
        assertEquals(2, (emitted[2] as StreamData).segmentIndex)
    }

    @Test
    fun `CutPoint triggers StreamEnd and StreamStart`() = runTest(UnconfinedTestDispatcher()) {
        val emitted = mutableListOf<DataChannelMsg>()
        val emitter = OrderedEmitter(42) { emitted.add(it) }

        emitter.complete(0, success(42, 0))
        emitter.complete(1, cutPoint(42, EndReason.NewInit))

        assertEquals(3, emitted.size)
        assertEquals(0, (emitted[0] as StreamData).segmentIndex)
        assertTrue(emitted[1] is StreamEnd)
        assertEquals(EndReason.NewInit, (emitted[1] as StreamEnd).reason)
        assertTrue(emitted[2] is StreamStart)
    }

    @Test
    fun `Failed result is skipped`() = runTest(UnconfinedTestDispatcher()) {
        val emitted = mutableListOf<DataChannelMsg>()
        val emitter = OrderedEmitter(42) { emitted.add(it) }

        emitter.complete(0, failed(0, 42))
        emitter.complete(1, success(42, 1))

        // failed idx=0 is skipped, should move to idx=1
        assertEquals(1, emitted.size)
        assertEquals(1, (emitted[0] as StreamData).segmentIndex)
    }

    @Test
    fun `UserStop CutPoint does not emit StreamStart after StreamEnd`() = runTest(UnconfinedTestDispatcher()) {
        val emitted = mutableListOf<DataChannelMsg>()
        val emitter = OrderedEmitter(42) { emitted.add(it) }

        emitter.complete(0, success(42, 0))
        emitter.complete(1, cutPoint(42, EndReason.UserStop))

        assertEquals(2, emitted.size)
        assertTrue(emitted[0] is StreamData)
        assertTrue(emitted[1] is StreamEnd)
        // UserStop → no StreamStart follow-up
    }

    @Test
    fun `buffer holds out-of-order until contiguous prefix is ready`() = runTest(UnconfinedTestDispatcher()) {
        val emitted = mutableListOf<DataChannelMsg>()
        val emitter = OrderedEmitter(42) { emitted.add(it) }

        emitter.complete(5, success(42, 5))
        emitter.complete(3, success(42, 3))
        emitter.complete(4, success(42, 4))
        // idx 0-2 not arrived, nothing emitted
        assertEquals(0, emitted.size)
    }
}
