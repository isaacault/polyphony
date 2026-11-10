package app.polyphony.core.domain.connection

import app.polyphony.core.domain.connection.ConnectionSample.Source
import app.polyphony.core.domain.connection.ConnectionSample.Trigger
import app.polyphony.core.domain.transcode.AudioCodec
import app.polyphony.core.domain.transcode.NetworkKind
import app.polyphony.core.domain.transcode.TranscodeDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionWindowTest {
    private val cellular = NetworkContext(NetworkKind.CELLULAR, metered = true, atHome = false)
    private val wifi = NetworkContext(NetworkKind.WIFI, metered = false)
    private val opus = StreamContext("s1", "builtin.high", AudioCodec.OPUS, 160, TranscodeDecision.Reason.CELLULAR_RULE)

    /** One second of 10 KB reads every 10 ms on [stream], from [fromMs]: 800 kbit/s. */
    private fun ConnectionWindow.readSecond(stream: Long, fromMs: Long) {
        for (i in 0 until 100) {
            val t = (fromMs + i * 10L) * 1_000_000L
            onNetworkRead(stream, t, t + 10_000_000L, 1_000)
        }
    }

    @Test
    fun `a window becomes a sample of what was measured in it`() {
        val w = ConnectionWindow()
        w.open(0, cellular)
        w.onNetworkOpened(7)
        w.onResponse(180); w.onResponse(220); w.onResponse(900)
        w.readSecond(7, 1_000)
        w.onStall(true, 10_000); w.onStall(false, 12_000)
        w.onStall(true, 20_000); w.onStall(false, 21_500)
        val s = w.close(30_000, Trigger.PERIODIC, opus)!!
        assertEquals(0L, s.startedAtMs)
        assertEquals(30_000L, s.durationMs)
        assertEquals(NetworkKind.CELLULAR, s.network)
        assertTrue(s.metered)
        assertEquals(false, s.atHome)
        assertEquals(220, s.responseMs)
        assertEquals(100_000L, s.networkBytes)
        assertEquals(1_000L, s.networkMs)
        assertEquals(800, s.throughputKbps)
        assertEquals(2, s.stalls)
        assertEquals(3_500L, s.stallMs)
        assertEquals("builtin.high", s.profileId)
        assertEquals(AudioCodec.OPUS, s.codec)
        assertEquals(160, s.maxBitrateKbps)
        assertEquals(Source.NETWORK, s.source)
        assertFalse(s.capped)
        assertFalse(w.isOpen)
    }

    @Test
    fun `cache and download reads are kept apart from the throughput`() {
        val w = ConnectionWindow()
        w.open(0, wifi)
        w.onCacheRead(5_000_000)
        w.onDownloadRead(3_000_000)
        val s = w.close(30_000, Trigger.PERIODIC, null)!!
        assertEquals(0L, s.networkBytes)
        assertNull(s.throughputKbps)
        assertEquals(5_000_000L, s.cachedBytes)
        assertEquals(3_000_000L, s.downloadedBytes)
        assertEquals(Source.MIXED, s.source)

        w.open(30_000, wifi)
        w.onCacheRead(1_000_000)
        assertEquals(Source.CACHE, w.close(60_000, Trigger.PERIODIC, null)!!.source)
    }

    @Test
    fun `a stall across a boundary counts once and its time in each window`() {
        val w = ConnectionWindow()
        w.open(0, cellular)
        w.onStall(true, 25_000)
        val first = w.roll(30_000, Trigger.PERIODIC, opus, cellular)!!
        assertEquals(1, first.stalls)
        assertEquals(5_000L, first.stallMs)
        w.onStall(false, 34_000)
        val second = w.close(60_000, Trigger.PERIODIC, opus)!!
        assertEquals(0, second.stalls)
        assertEquals(4_000L, second.stallMs)
    }

    @Test
    fun `the latency cap is timed, including one in force before the window`() {
        val w = ConnectionWindow()
        w.onCapped(true, -100_000)
        w.open(0, cellular)
        w.onCapped(false, 12_000)
        val s = w.roll(30_000, Trigger.PERIODIC, opus, cellular)!!
        assertTrue(s.capped)
        assertEquals(12_000L, s.cappedMs)
        val next = w.close(60_000, Trigger.PERIODIC, opus)!!
        assertFalse(next.capped)
        assertEquals(0L, next.cappedMs)
    }

    @Test
    fun `a network change ends the window on the old network`() {
        val w = ConnectionWindow()
        w.open(0, wifi)
        w.readSecond(1, 0)
        val s = w.roll(12_000, Trigger.NETWORK_CHANGE, null, cellular)!!
        assertEquals(NetworkKind.WIFI, s.network)
        assertEquals(Trigger.NETWORK_CHANGE, s.trigger)
        w.readSecond(1, 13_000)
        assertEquals(NetworkKind.CELLULAR, w.close(42_000, Trigger.PERIODIC, null)!!.network)
    }

    @Test
    fun `home or away learnt during the window is kept`() {
        val w = ConnectionWindow()
        w.open(0, wifi)
        w.updateNetwork(wifi.copy(atHome = true))
        w.updateNetwork(cellular)
        val s = w.close(30_000, Trigger.PERIODIC, null)!!
        assertEquals(true, s.atHome)
        assertEquals(NetworkKind.WIFI, s.network)
    }

    @Test
    fun `nothing is measured while the window is closed`() {
        val w = ConnectionWindow()
        w.readSecond(1, 0)
        w.onCacheRead(1_000)
        w.onResponse(100)
        w.onStall(true, 1_000); w.onStall(false, 2_000)
        w.open(10_000, wifi)
        val s = w.close(40_000, Trigger.PERIODIC, null)!!
        assertEquals(0L, s.networkBytes)
        assertEquals(0L, s.cachedBytes)
        assertNull(s.responseMs)
        assertEquals(0, s.stalls)
        assertEquals(0L, s.stallMs)
    }

    @Test
    fun `a short window without a stall is no sample`() {
        val w = ConnectionWindow()
        w.open(0, wifi)
        w.readSecond(1, 0)
        assertNull(w.close(3_000, Trigger.STOPPED, null))
        w.open(10_000, wifi)
        w.onStall(true, 11_000)
        assertNotNull(w.close(13_000, Trigger.STOPPED, null))
        assertNull(w.close(14_000, Trigger.STOPPED, null))
    }

    @Test
    fun `median of an even count is the mean of the middle two`() {
        assertEquals(15L, ConnectionWindow.median(listOf(30, 10, 20, 10)))
        assertNull(ConnectionWindow.median(emptyList()))
    }
}
