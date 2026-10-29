package app.polyphony.ui.browse.album

import app.polyphony.core.domain.model.Track
import app.polyphony.ui.browse.album.AlbumTrackDetails.Credit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumTrackDetailsTest {

    private fun track(title: String, artist: String?, container: String? = "flac", bitDepth: Int? = 16) =
        Track(id = title, serverId = "s", title = title, artistName = artist, container = container, bitDepth = bitDepth,
              sampleRateHz = 44_100)

    @Test
    fun `the album's own artist isn't repeated`() {
        assertNull(AlbumTrackDetails.credit(track("Marvins Room", "Drake"), "Drake"))
        assertNull(AlbumTrackDetails.credit(track("Marvins Room", "drake"), "Drake"))
        assertNull(AlbumTrackDetails.credit(track("Intro", null), "Drake"))
    }

    @Test
    fun `featured artists show on their own`() {
        assertEquals(Credit.Featuring("Future"), AlbumTrackDetails.credit(track("Way 2 Sexy", "Drake feat. Future"), "Drake"))
        assertEquals(Credit.Featuring("Future & Young Thug"),
            AlbumTrackDetails.credit(track("Way 2 Sexy", "Drake, Future & Young Thug"), "Drake"))
        assertEquals(Credit.Featuring("Rihanna"), AlbumTrackDetails.credit(track("Take Care", "Drake & Rihanna"), "Drake"))
        assertEquals("a name starting with Ft isn't clipped", Credit.Featuring("Ftoo"),
            AlbumTrackDetails.credit(track("Song", "Drake ft. Ftoo"), "Drake"))
    }

    @Test
    fun `nothing extra when the title already says who's featured`() {
        assertNull(AlbumTrackDetails.credit(track("Jimmy Cooks (feat. 21 Savage)", "Drake, 21 Savage"), "Drake"))
    }

    @Test
    fun `a band's name isn't taken apart`() {
        assertNull(AlbumTrackDetails.credit(track("Dog Days Are Over", "Florence + the Machine"), "Florence + the Machine"))
        assertNull(AlbumTrackDetails.credit(track("September", "Earth, Wind & Fire"), "Earth, Wind & Fire"))
    }

    @Test
    fun `a compilation shows each track's artist`() {
        assertEquals(Credit.Other("ABBA"), AlbumTrackDetails.credit(track("Waterloo", "ABBA"), "Various Artists"))
        assertEquals(Credit.Other("U2"), AlbumTrackDetails.credit(track("One", "U2"), "Lil Yachty"))
    }

    @Test
    fun `quality shows once for the album, on a track only where it differs`() {
        val tracks = listOf(track("a", "X"), track("b", "X"), track("c", "X", bitDepth = 24))
        val album = AlbumTrackDetails.albumQuality(tracks)
        assertEquals("FLAC", album)
        assertNull(AlbumTrackDetails.trackQuality(tracks[0], album))
        assertEquals("HI-RES", AlbumTrackDetails.trackQuality(tracks[2], album))
        assertNull(AlbumTrackDetails.albumQuality(listOf(track("mp3", "X", container = "mp3"))))
    }

    @Test
    fun `on a hi-res album the CD-resolution track says 16-BIT, not FLAC`() {
        val tracks = listOf(track("Speaker", "X"), track("b", "X", bitDepth = 24), track("c", "X", bitDepth = 24))
        val album = AlbumTrackDetails.albumQuality(tracks)
        assertEquals("HI-RES", album)
        assertEquals("16-BIT", AlbumTrackDetails.trackQuality(tracks[0], album))
        assertNull(AlbumTrackDetails.trackQuality(tracks[1], album))
        assertNull(AlbumTrackDetails.trackQuality(track("lossy", "X", container = "mp3"), album))
    }
}
