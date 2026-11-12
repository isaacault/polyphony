package app.polyphony.core.playback.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LongTrackPositionsTest {
    private val hour = 60 * 60_000L

    @Test fun `a long song left part way resumes there`() {
        val p = LongTrackPositions()
        p.left("mix", positionMs = 34 * 60_000L, durationMs = hour)
        assertEquals(34 * 60_000L, p.resumeAt("mix", hour))
    }

    @Test fun `short songs, the first minute and the last minute aren't kept`() {
        val p = LongTrackPositions()
        p.left("song", positionMs = 2 * 60_000L, durationMs = 4 * 60_000L)
        p.left("intro", positionMs = 30_000L, durationMs = hour)
        p.left("outro", positionMs = hour - 30_000L, durationMs = hour)
        assertNull(p.resumeAt("song", 4 * 60_000L))
        assertNull(p.resumeAt("intro", hour))
        assertNull(p.resumeAt("outro", hour))
    }

    @Test fun `going back to the start or finishing forgets the place`() {
        val p = LongTrackPositions()
        p.left("mix", 34 * 60_000L, hour)
        p.left("mix", 10_000L, hour)
        assertNull(p.resumeAt("mix", hour))
        p.left("set", 34 * 60_000L, hour)
        p.finished("set")
        assertNull(p.resumeAt("set", hour))
    }

    @Test fun `the newest hundred are kept, and survive a restart`() {
        val p = LongTrackPositions()
        for (i in 0..LongTrackPositions.MAX) p.left("t$i", 5 * 60_000L + i, hour)
        assertNull("the oldest went", p.resumeAt("t0", hour))
        val again = LongTrackPositions().apply { load(p.serialize() + "\nnot a line\n") }
        assertEquals(5 * 60_000L + 100, again.resumeAt("t100", hour))
        assertEquals(5 * 60_000L + 1, again.resumeAt("t1", hour))
    }
}
