package app.polyphony.core.domain.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThroughputMeterTest {
    private val ms = 1_000_000L

    /** [reads] back-to-back reads of [bytes] each, every [everyMs], each blocking [readMs]. */
    private fun ThroughputMeter.run(stream: Long, fromMs: Long, reads: Int, bytes: Int, everyMs: Long, readMs: Long = everyMs): Long {
        var t = fromMs
        repeat(reads) {
            onRead(stream, t * ms, (t + readMs) * ms, bytes)
            t += everyMs
        }
        return t
    }

    @Test
    fun `steady reads measure bytes over the time they took`() {
        val m = ThroughputMeter()
        m.onOpened(1)
        // 100 reads of 10 KB, 10 ms each: 1 000 000 bytes in 1 s = 8000 kbit/s.
        m.run(1, fromMs = 0, reads = 100, bytes = 10_000, everyMs = 10)
        assertEquals(1_000_000L, m.bytes)
        assertEquals(1_000L * ms, m.busyNs)
        assertEquals(8_000, m.throughputKbps())
    }

    @Test
    fun `the time the player spends parsing between reads counts`() {
        val m = ThroughputMeter()
        m.onOpened(1)
        // Reads take 1 ms, the next one starts 10 ms later: the run is 10 ms per read.
        m.run(1, fromMs = 0, reads = 100, bytes = 10_000, everyMs = 10, readMs = 1)
        assertEquals((99 * 10 + 1).toLong() * ms, m.busyNs)
    }

    @Test
    fun `after a pause what the socket held is not counted until a read waits again`() {
        val m = ThroughputMeter()
        m.onOpened(1)
        var t = m.run(1, fromMs = 0, reads = 50, bytes = 10_000, everyMs = 10) // 500 ms, 500 KB
        t += 60_000 // the buffer is full; the player stops reading for a minute
        // The socket filled meanwhile: 20 instant reads of 10 KB.
        repeat(20) { m.onRead(1, t * ms, t * ms + 50_000, 10_000); t += 1 }
        assertEquals(500_000L, m.bytes)
        // Then reads wait for the network again, at the same rate as before.
        m.run(1, fromMs = t, reads = 50, bytes = 10_000, everyMs = 10)
        assertEquals(1_000_000L, m.bytes)
        assertEquals(8_000, m.throughputKbps())
    }

    @Test
    fun `reads at the same time on two streams count the time once`() {
        val m = ThroughputMeter()
        m.onOpened(1); m.onOpened(2)
        // Interleaved: both streams read 10 KB over the same 10 ms slots, for 1 s.
        for (i in 0 until 100) {
            val t = i * 10L
            m.onRead(1, t * ms, (t + 10) * ms, 10_000)
            m.onRead(2, t * ms, (t + 10) * ms, 10_000)
        }
        assertEquals(2_000_000L, m.bytes)
        assertEquals(1_000L * ms, m.busyNs)
        assertEquals(16_000, m.throughputKbps())
    }

    @Test
    fun `a reopened stream starts a fresh run`() {
        val m = ThroughputMeter()
        m.onOpened(1)
        m.run(1, fromMs = 0, reads = 10, bytes = 10_000, everyMs = 10)
        m.onClosed(1)
        m.onOpened(1)
        // The request took 5 s to answer: that is response time, not reading time.
        m.run(1, fromMs = 5_100, reads = 10, bytes = 10_000, everyMs = 10)
        assertEquals(200L * ms, m.busyNs)
    }

    @Test
    fun `too little read says nothing`() {
        val m = ThroughputMeter()
        m.onRead(1, 0, 300 * ms, 4_000)
        assertNull(m.throughputKbps())
        assertNull(ThroughputMeter.throughputKbps(bytes = 1_000_000, busyNs = 10 * ms))
    }

    @Test
    fun `reset starts a window afresh`() {
        val m = ThroughputMeter()
        m.run(1, fromMs = 0, reads = 100, bytes = 10_000, everyMs = 10)
        m.reset()
        assertEquals(0L, m.bytes)
        assertEquals(0L, m.busyNs)
        assertNull(m.throughputKbps())
    }
}
