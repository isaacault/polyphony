package app.polyphony.core.domain.playback

import app.polyphony.core.domain.model.PlayContext
import app.polyphony.core.domain.model.RecentContext
import app.polyphony.core.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class ResumePointTest {
    private val context = PlayContext(PlayContext.Kind.ALBUM, "a1", "srv", "Album")
    private val tracks = (1..4).map { Track(id = "t$it", serverId = "srv", title = "Track $it", durationMs = 200_000L) }

    private fun entry(trackId: String?, index: Int = 0, position: Long = 0L) =
        RecentContext(context, trackId = trackId, trackIndex = index, positionMs = position)

    @Test
    fun `finds the track by id and keeps the position`() {
        assertEquals(ResumePoint(2, 61_000L), ResumePoint.of(tracks, entry("t3", index = 0, position = 61_000L)))
    }

    @Test
    fun `falls back to the remembered index when the track is gone`() {
        assertEquals(ResumePoint(1, 0L), ResumePoint.of(tracks, entry("missing", index = 1, position = 50_000L)))
        assertEquals(ResumePoint(3, 0L), ResumePoint.of(tracks, entry("missing", index = 99)))
    }

    @Test
    fun `starts over when the context was finished or unknown`() {
        assertEquals(ResumePoint.START, ResumePoint.of(tracks, entry(null, index = 3, position = 10_000L)))
        assertEquals(ResumePoint.START, ResumePoint.of(tracks, null))
        assertEquals(ResumePoint.START, ResumePoint.of(emptyList(), entry("t1")))
    }

    @Test
    fun `skips to the next track when the remembered one was almost over`() {
        assertEquals(ResumePoint(2, 0L), ResumePoint.of(tracks, entry("t2", position = 198_500L)))
        // Last track nearly done: the whole context is done.
        assertEquals(ResumePoint.START, ResumePoint.of(tracks, entry("t4", position = 199_000L)))
    }

    @Test
    fun `clamps positions to the track`() {
        assertEquals(ResumePoint(0, 0L), ResumePoint.of(tracks, entry("t1", position = -5L)))
        val untimed = tracks.map { it.copy(durationMs = 0L) }
        assertEquals(ResumePoint(0, 9_999_999L), ResumePoint.of(untimed, entry("t1", position = 9_999_999L)))
    }
}
