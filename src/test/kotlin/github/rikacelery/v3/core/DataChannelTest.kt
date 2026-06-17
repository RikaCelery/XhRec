package github.rikacelery.v3.core

import github.rikacelery.v3.data.DataChannelMsg
import github.rikacelery.v3.data.StreamEnd
import github.rikacelery.v3.data.StreamStart
import github.rikacelery.v3.events.EndReason
import github.rikacelery.v3.hooks.DataHook
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataChannelTest {

    @Test
    fun `send-receive preserves order`() = runTest(UnconfinedTestDispatcher()) {
        val ch = DataChannel()
        val received = mutableListOf<DataChannelMsg>()
        val job = launch {
            received.add(ch.receive())
            received.add(ch.receive())
        }

        val m1 = StreamStart(1, "a", Instant.now(), "highest")
        val m2 = StreamEnd(1, EndReason.UserStop)
        ch.send(m1)
        ch.send(m2)
        ch.close()
        job.join()

        assertEquals(2, received.size)
        assertEquals(m1, received[0])
        assertEquals(m2, received[1])
    }

    @Test
    fun `hook returns null drops message`() = runTest(UnconfinedTestDispatcher()) {
        val ch = DataChannel()
        ch.installHook(object : DataHook {
            override suspend fun intercept(msg: DataChannelMsg): DataChannelMsg? {
                return if (msg is StreamStart) null else msg
            }
        })

        val received = mutableListOf<DataChannelMsg>()
        val job = launch {
            received.add(ch.receive())
        }

        ch.send(StreamStart(1, "x", Instant.now(), "highest"))
        ch.send(StreamEnd(1, EndReason.UserStop))
        ch.close()
        job.join()

        assertEquals(1, received.size)
        assertTrue(received[0] is StreamEnd)
    }
}
