package app.polyphony.core.playback.timer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {

    private class Fixture(val scope: kotlinx.coroutines.CoroutineScope, val clock: () -> Long) {
        var paused = 0
        val volumes = mutableListOf<Float>()
        val timer = SleepTimer(
            scope = scope,
            pause = { paused++ },
            setVolume = { volumes += it },
            clock = clock,
            fadeMs = 8_000L,
            tickMs = 1_000L,
        )
    }

    @Test
    fun `a timed sleep fades the volume down then pauses and restores it`() = runTest {
        val f = Fixture(backgroundScope) { testScheduler.currentTime }
        f.timer.set(endsAtEpochMs = 30_000L)
        f.volumes.clear() // the initial restore-to-1 on set

        advanceTimeBy(21_900) // just before the fade window (ends-at 30 000, fade 8 000 => starts 22 000)
        assertTrue("no fade before the window", f.volumes.isEmpty())

        advanceTimeBy(4_000) // into the ramp (now 25 900): ~4 100 ms left of 8 000 => ~0.5
        assertTrue("fade started", f.volumes.isNotEmpty())
        assertTrue("volume ramps below full", f.volumes.last() < 0.9f)

        advanceTimeBy(5_000) // past the end
        assertEquals("paused once", 1, f.paused)
        assertEquals("volume restored to full after firing", 1f, f.volumes.last(), 0f)
        assertNull(f.timer.endsAtEpochMs.value)
    }

    @Test
    fun `cancelling mid-fade restores the volume and never pauses`() = runTest {
        val f = Fixture(backgroundScope) { testScheduler.currentTime }
        f.timer.set(endsAtEpochMs = 10_000L) // fade window starts at 2 000 ms
        advanceTimeBy(4_100)
        assertTrue("fading", f.volumes.any { it < 1f })
        f.timer.cancel()
        advanceTimeBy(10_000)
        assertEquals("never paused", 0, f.paused)
        assertEquals("volume restored on cancel", 1f, f.volumes.last(), 0f)
    }

    @Test
    fun `finish-track mode waits for the next transition and does not fade`() = runTest {
        val f = Fixture(backgroundScope) { testScheduler.currentTime }
        f.timer.set(endsAtEpochMs = 5_000L, finishTrack = true)
        f.volumes.clear()
        advanceTimeBy(6_000) // the wall-clock time passes
        assertEquals("must not pause until a track change", 0, f.paused)
        assertTrue("no fade in finish-track mode", f.volumes.all { it == 1f })
        f.timer.onTrackChanged()
        assertEquals("pauses on the track change", 1, f.paused)
    }
}
