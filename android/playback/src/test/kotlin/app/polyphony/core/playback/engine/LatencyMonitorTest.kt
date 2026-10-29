package app.polyphony.core.playback.engine

import app.polyphony.core.domain.transcode.AdaptiveLatencySettings
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
}
