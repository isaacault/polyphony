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
    fun `end of track is a finish-track timer that is already due`() = runTest {
        val f = Fixture(backgroundScope) { testScheduler.currentTime }
        f.timer.set(endsAtEpochMs = testScheduler.currentTime, finishTrack = true)
        advanceTimeBy(600_000) // however long the track runs (paused, slowed down) it never fires on time alone
        assertEquals("still playing", 0, f.paused)
        f.timer.onTrackChanged()
        assertEquals("paused at the track change", 1, f.paused)
        assertNull(f.timer.endsAtEpochMs.value)
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

    @Test
    fun `finish-track mode fades over the end of the track when its length is known`() = runTest {
        var remaining: Long? = 60_000L
        var paused = 0
        val volumes = mutableListOf<Float>()
        val timer = SleepTimer(
            scope = backgroundScope, pause = { paused++ }, setVolume = { volumes += it },
            clock = { testScheduler.currentTime }, fadeMs = 8_000L, tickMs = 1_000L, remainingInTrackMs = { remaining },
        )
        timer.set(endsAtEpochMs = 0L, finishTrack = true)
        volumes.clear()
        advanceTimeBy(2_000)
        assertTrue("no fade with a minute to go", volumes.isEmpty())
        remaining = 4_000L
        advanceTimeBy(1_100)
        assertEquals("halfway down with half the fade left", 0.5f, volumes.last(), 0.01f)
        timer.onTrackChanged()
        assertEquals(1, paused)
        assertEquals("full again once paused", 1f, volumes.last(), 0f)
    }

    @Test
    fun `extending moves the end and brings the volume back`() = runTest {
        val f = Fixture(backgroundScope) { testScheduler.currentTime }
        f.timer.set(endsAtEpochMs = 10_000L)
        advanceTimeBy(6_100) // fading
        assertTrue(f.volumes.last() < 1f)
        f.timer.extend(600_000L)
        assertEquals(610_000L, f.timer.endsAtEpochMs.value)
        assertEquals("back to full", 1f, f.volumes.last(), 0f)
        advanceTimeBy(10_000)
        assertEquals("didn't fire at the old time", 0, f.paused)
    }

    @Test
    fun `extending a timer already past runs from now`() = runTest {
        val f = Fixture(backgroundScope) { testScheduler.currentTime }
        f.timer.set(endsAtEpochMs = 0L, finishTrack = true)
        advanceTimeBy(5_000)
        f.timer.extend(60_000L)
        assertEquals(65_000L, f.timer.endsAtEpochMs.value)
    }

    @Test
    fun `nothing to extend without a timer`() = runTest {
        val f = Fixture(backgroundScope) { testScheduler.currentTime }
        f.timer.extend(60_000L)
        assertNull(f.timer.endsAtEpochMs.value)
    }
}
