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

    @Test fun `what changed is said, for the server's bookmark`() {
        val p = LongTrackPositions()
        assertEquals(LongTrackPositions.Change.KEPT, p.left("mix", 34 * 60_000L, hour))
        assertEquals(LongTrackPositions.Change.FORGOTTEN, p.left("mix", 10_000L, hour))
        assertEquals(LongTrackPositions.Change.NONE, p.left("mix", 10_000L, hour))
        assertEquals(LongTrackPositions.Change.NONE, p.left("song", 60_000L, 4 * 60_000L))
    }

    @Test fun `the server's bookmarks fill in, without overriding this phone's places`() {
        val p = LongTrackPositions()
        p.left("mix", 34 * 60_000L, hour)
        p.merge(mapOf("mix" to 5 * 60_000L, "set" to 12 * 60_000L, "barely" to 2_000L))
        assertEquals(34 * 60_000L, p.resumeAt("mix", hour))
        assertEquals(12 * 60_000L, p.resumeAt("set", hour))
        assertNull(p.resumeAt("barely", hour))
    }

    @Test fun `a place forgotten here isn't brought back by a bookmark the server still has`() {
        val p = LongTrackPositions()
        p.left("mix", 34 * 60_000L, hour)
        p.finished("mix")
        p.merge(mapOf("mix" to 34 * 60_000L))   // the delete failed, offline
        assertNull(p.resumeAt("mix", hour))
        val restarted = LongTrackPositions().apply { load(p.serialize()) }
        restarted.merge(mapOf("mix" to 34 * 60_000L))
        assertNull("still after a restart", restarted.resumeAt("mix", hour))
        restarted.bookmarkDeleted("mix")
        restarted.merge(mapOf("mix" to 20 * 60_000L))   // left on another device since
        assertEquals(20 * 60_000L, restarted.resumeAt("mix", hour))
    }

    @Test fun `the phone's own places outlast the server's bookmarks`() {
        val p = LongTrackPositions()
        p.left("mine", 30 * 60_000L, hour)
        p.merge((1..150).associate { "server$it" to 5 * 60_000L })
        assertEquals(30 * 60_000L, p.resumeAt("mine", hour))
    }

    @Test fun `a short song never resumes, whatever a bookmark says`() {
        val p = LongTrackPositions()
        p.merge(mapOf("song" to 90_000L))
        assertNull(p.resumeAt("song", 4 * 60_000L))
        assertNull("length unknown", p.resumeAt("song", 0L))
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
