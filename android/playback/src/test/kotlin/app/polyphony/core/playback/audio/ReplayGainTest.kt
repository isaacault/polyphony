package app.polyphony.core.playback.audio

import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.repository.ReplayGainMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayGainTest {
    private fun track(trackDb: Double? = null, albumDb: Double? = null, trackPeak: Double? = null, albumPeak: Double? = null, albumId: String = "a") =
        Track(id = "t", serverId = "s", title = "T", albumId = albumId, replayGainTrackDb = trackDb, replayGainAlbumDb = albumDb, replayGainTrackPeak = trackPeak, replayGainAlbumPeak = albumPeak)

    @Test
    fun `off and missing tags give unity`() {
        assertEquals(1f, ReplayGain.volumeFor(track(-6.0), ReplayGainMode.OFF, 0f, true, false), 0.0001f)
        assertEquals(1f, ReplayGain.volumeFor(track(), ReplayGainMode.TRACK, 0f, true, false), 0.0001f)
        assertEquals(1f, ReplayGain.volumeFor(null, ReplayGainMode.TRACK, 0f, true, false), 0.0001f)
    }

    @Test
    fun `negative gain attenuates`() {
        assertEquals(0.501f, ReplayGain.volumeFor(track(-6.0), ReplayGainMode.TRACK, 0f, true, false), 0.002f)
    }

    @Test
    fun `positive gain clamps at unity`() {
        assertEquals(1f, ReplayGain.volumeFor(track(+4.0), ReplayGainMode.TRACK, 0f, false, false), 0.0001f)
    }

    @Test
    fun `preamp is added`() {
        assertEquals(0.708f, ReplayGain.volumeFor(track(-6.0), ReplayGainMode.TRACK, 3f, true, false), 0.002f)
    }

    @Test
    fun `clipping prevention respects peak`() {
        // -1 dB gain would give 0.891, but peak 0.95 allows at most 1/0.95; peak 2.0 limits to 0.5
        assertEquals(0.891f, ReplayGain.volumeFor(track(-1.0, trackPeak = 0.95), ReplayGainMode.TRACK, 0f, true, false), 0.002f)
        assertEquals(0.5f, ReplayGain.volumeFor(track(-1.0, trackPeak = 2.0), ReplayGainMode.TRACK, 0f, true, false), 0.002f)
    }

    @Test
    fun `auto picks album gain only when neighbour shares the album`() {
        val t = track(trackDb = -6.0, albumDb = -12.0)
        assertEquals(0.251f, ReplayGain.volumeFor(t, ReplayGainMode.AUTO, 0f, true, sameAlbumAsNeighbour = true), 0.002f)
        assertEquals(0.501f, ReplayGain.volumeFor(t, ReplayGainMode.AUTO, 0f, true, sameAlbumAsNeighbour = false), 0.002f)
    }

    @Test
    fun `same album helper`() {
        assertEquals(true, ReplayGain.sameAlbum(track(albumId = "x"), track(albumId = "x")))
        assertEquals(false, ReplayGain.sameAlbum(track(albumId = "x"), track(albumId = "y")))
        assertEquals(false, ReplayGain.sameAlbum(track(albumId = "x"), null))
    }
}
