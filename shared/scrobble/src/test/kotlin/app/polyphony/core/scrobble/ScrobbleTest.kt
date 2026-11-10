package app.polyphony.core.scrobble

import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Artist
import app.polyphony.core.domain.model.Track
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrobbleTest {
    private val track = Track(
        id = "t1", serverId = "s", title = "One More Time", albumId = "al1", albumName = "Discovery", artistId = "ar1",
        artistName = "Daft Punk", albumArtistName = "Daft Punk", trackNumber = 1, discNumber = 1, durationMs = 320_000,
        musicBrainzId = "9B5B8D7C-0C46-4D29-9A3B-8C8E0F1F6A11",
    )
    private val album = Album(id = "al1", serverId = "s", name = "Discovery", musicBrainzId = "48117b82-8d5c-4f2b-8c8a-6e9d2a0f9b22")
    private val artist = Artist(id = "ar1", serverId = "s", name = "Daft Punk", musicBrainzId = "056e4f3e-d505-4dad-8ec1-d04f521cbb56")

    @Test
    fun `a library track maps with the ids its server gave`() {
        val s = Scrobble.of(track, 1_000, album, artist)!!
        assertEquals("Daft Punk", s.artist)
        assertEquals("One More Time", s.track)
        assertEquals("Discovery", s.album)
        assertNull("the same as the artist: left out", s.albumArtist)
        assertEquals(320_000L, s.durationMs)
        assertEquals("9b5b8d7c-0c46-4d29-9a3b-8c8e0f1f6a11", s.recordingMbid)
        assertEquals("48117b82-8d5c-4f2b-8c8a-6e9d2a0f9b22", s.releaseMbid)
        assertEquals(listOf("056e4f3e-d505-4dad-8ec1-d04f521cbb56"), s.artistMbids)
    }

    @Test
    fun `ids only when well-formed and from the track's own album and artist`() {
        val s = Scrobble.of(
            track.copy(musicBrainzId = ""), 1_000, album.copy(id = "other"), artist.copy(musicBrainzId = "not-an-id"),
        )!!
        assertNull(s.recordingMbid)
        assertNull(s.releaseMbid)
        assertEquals(emptyList<String>(), s.artistMbids)
    }

    @Test
    fun `a compilation keeps its album artist, and an anonymous track isn't scrobbled`() {
        val s = Scrobble.of(track.copy(artistName = "Romanthony", albumArtistName = "Daft Punk"), 1_000)!!
        assertEquals("Romanthony", s.artist)
        assertEquals("Daft Punk", s.albumArtist)
        assertEquals("the album artist stands in", "Daft Punk", Scrobble.of(track.copy(artistName = null), 1_000)!!.artist)
        assertNull(Scrobble.of(track.copy(artistName = null, albumArtistName = null), 1_000))
        assertNull(Scrobble.of(track.copy(title = " "), 1_000))
    }

    @Test
    fun `a queued scrobble reads back as it was written`() {
        val s = Scrobble.of(track, 1_000, album, artist)!!
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(s, json.decodeFromString(Scrobble.serializer(), json.encodeToString(Scrobble.serializer(), s)))
    }
}
