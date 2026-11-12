package app.polyphony.core.playback.engine

import app.polyphony.core.domain.transcode.AdaptiveLatencySettings
import app.polyphony.core.domain.transcode.NetworkKind
import app.polyphony.core.domain.transcode.NetworkState
import app.polyphony.core.playback.util.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyMonitorTest {

    private class FakeClock(var now: Long = 1_000_000L) : Clock {
        override fun nowMs(): Long = now
        fun advance(ms: Long) { now += ms }
    }

    private fun monitor(clock: FakeClock, settings: AdaptiveLatencySettings = AdaptiveLatencySettings()): LatencyMonitor =
        LatencyMonitor(clock).also { it.settings = settings }

    private fun LatencyMonitor.stall(clock: FakeClock, durationMs: Long) {
        onStallStarted(clock.now)
        clock.advance(durationMs)
        onStallEnded(clock.now)
    }

    @Test
    fun `threshold stalls inside window trip degraded`() {
        val clock = FakeClock()
        val m = monitor(clock, AdaptiveLatencySettings(stallThreshold = 3, windowSeconds = 120))
        repeat(2) { m.stall(clock, 500); clock.advance(10_000) }
        assertFalse(m.degraded.value)
        m.stall(clock, 500)
        assertTrue(m.degraded.value)
    }

    @Test
    fun `stalls outside the window do not count`() {
        val clock = FakeClock()
        val m = monitor(clock, AdaptiveLatencySettings(stallThreshold = 3, windowSeconds = 60, longStallMs = 60_000))
        m.stall(clock, 500); clock.advance(61_000)
        m.stall(clock, 500); clock.advance(61_000)
        m.stall(clock, 500)
        assertFalse(m.degraded.value)
    }

    @Test
    fun `a single long stall trips on end`() {
        val clock = FakeClock()
        val m = monitor(clock, AdaptiveLatencySettings(stallThreshold = 10, longStallMs = 4_000))
        m.stall(clock, 4_500)
        assertTrue(m.degraded.value)
    }

    @Test
    fun `a long stall trips on tick while still ongoing`() {
        val clock = FakeClock()
        val m = monitor(clock, AdaptiveLatencySettings(stallThreshold = 10, longStallMs = 4_000))
        m.onStallStarted(clock.now)
        clock.advance(3_000); m.tick(clock.now)
        assertFalse(m.degraded.value)
        clock.advance(1_500); m.tick(clock.now)
        assertTrue(m.degraded.value)
    }

    @Test
    fun `recovers after quiet period`() {
        val clock = FakeClock()
        val m = monitor(clock, AdaptiveLatencySettings(stallThreshold = 1, recoverAfterSeconds = 600))
        m.stall(clock, 500)
        assertTrue(m.degraded.value)
        clock.advance(599_000); m.tick(clock.now)
        assertTrue(m.degraded.value)
        clock.advance(2_000); m.tick(clock.now)
        assertFalse(m.degraded.value)
        assertEquals(1, m.stallCount)
    }

    @Test
    fun `manual-only recovery when recoverAfterSeconds is zero`() {
        val clock = FakeClock()
        val m = monitor(clock, AdaptiveLatencySettings(stallThreshold = 1, recoverAfterSeconds = 0))
        m.stall(clock, 500)
        clock.advance(3_600_000); m.tick(clock.now)
        assertTrue(m.degraded.value)
        m.reset()
        assertFalse(m.degraded.value)
    }

    @Test
    fun `disabled settings ignore stalls and clear state`() {
        val clock = FakeClock()
        val m = monitor(clock, AdaptiveLatencySettings(stallThreshold = 1))
        m.stall(clock, 500)
        assertTrue(m.degraded.value)
        m.settings = AdaptiveLatencySettings(enabled = false)
        assertFalse(m.degraded.value)
        m.stall(clock, 10_000)
        assertFalse(m.degraded.value)
    }

    @Test
    fun `reset clears the window so old stalls stop counting`() {
        val clock = FakeClock()
        val m = monitor(clock, AdaptiveLatencySettings(stallThreshold = 2))
        m.stall(clock, 100)
        m.reset()
        m.stall(clock, 100)
        assertFalse(m.degraded.value)
        m.stall(clock, 100)
        assertTrue(m.degraded.value)
    }

    @Test
    fun `losing the network mid-stream counts as failing, and time offline isn't recovery`() {
        val clock = FakeClock()
        val m = monitor(clock, AdaptiveLatencySettings(recoverAfterSeconds = 600))
        m.onConnectionLost(clock.now)
        assertTrue(m.degraded.value)
        clock.advance(30 * 60_000L)   // half an hour of cached songs in a dead zone
        m.tick(clock.now)
        assertTrue("still degraded while offline", m.degraded.value)
        m.onConnectionBack(clock.now)
        clock.advance(9 * 60_000L)
        m.tick(clock.now)
        assertTrue("recovery counts from the network's return", m.degraded.value)
        clock.advance(2 * 60_000L)
        m.tick(clock.now)
        assertFalse(m.degraded.value)
    }

    private val cellular = NetworkState(NetworkKind.CELLULAR, isMetered = true)
    private val wifi = NetworkState(NetworkKind.WIFI, isMetered = false)

    @Test
    fun `the same connection coming and going isn't a new one`() {
        assertEquals(LatencyMonitor.NetworkChange.LOST, LatencyMonitor.networkChange(cellular, true, NetworkState.OFFLINE, streaming = true))
        assertEquals("not streaming, nothing to judge", LatencyMonitor.NetworkChange.NOTHING, LatencyMonitor.networkChange(cellular, true, NetworkState.OFFLINE, streaming = false))
        assertEquals(LatencyMonitor.NetworkChange.BACK, LatencyMonitor.networkChange(cellular, false, cellular, streaming = true))
        assertEquals(LatencyMonitor.NetworkChange.NOTHING, LatencyMonitor.networkChange(cellular, true, cellular.copy(estimatedRttMs = 80), streaming = true))
    }

    @Test
    fun `another kind of connection, or another tariff, is judged afresh`() {
        assertEquals(LatencyMonitor.NetworkChange.NEW_CONNECTION, LatencyMonitor.networkChange(cellular, true, wifi, streaming = true))
        assertEquals("back on Wi-Fi after a dead zone", LatencyMonitor.NetworkChange.NEW_CONNECTION, LatencyMonitor.networkChange(cellular, false, wifi, streaming = true))
        assertEquals(LatencyMonitor.NetworkChange.NEW_CONNECTION, LatencyMonitor.networkChange(wifi, true, wifi.copy(isMetered = true), streaming = true))
        assertEquals("the first network seen", LatencyMonitor.NetworkChange.NOTHING, LatencyMonitor.networkChange(null, true, wifi, streaming = true))
    }
}
