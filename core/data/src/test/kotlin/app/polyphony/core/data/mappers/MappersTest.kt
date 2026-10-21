package app.polyphony.core.data.mappers

import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Artist
import app.polyphony.core.domain.model.Playlist
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerType
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.transcode.AudioCodec
import app.polyphony.core.domain.transcode.TranscodeProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MappersTest {

    @Test
    fun `server round trips including options`() {
        val server = Server(
            id = "s1", type = ServerType.JELLYFIN, name = "Home", baseUrl = "https://music.example",
            username = "me", credential = "token", userId = "u1", deviceId = "d1",
            options = mapOf("legacyAuth" to "true", "customHeaders" to "{\"X-A\":\"b\"}"), createdAt = 42L,
        )
        assertEquals(server, server.toEntity().toDomain())
    }

    @Test
    fun `unknown server type falls back to subsonic`() {
        val entity = Server("s", ServerType.SUBSONIC, "n", "u", "me", "p", null, "d").toEntity().copy(type = "PLEX")
        assertEquals(ServerType.SUBSONIC, entity.toDomain().type)
    }

    @Test
    fun `corrupt options json is tolerated`() {
        assertEquals(emptyMap<String, String>(), decodeOptions("{not json"))
        assertEquals(emptyMap<String, String>(), decodeOptions(""))
    }

    @Test
    fun `track round trips and carries generation`() {
        val track = Track(
            id = "t1", serverId = "s1", title = "Song", albumId = "a1", albumName = "Album", artistId = "ar1",
            artistName = "Artist", albumArtistName = "Artist", trackNumber = 3, discNumber = 1, year = 2001,
            genre = "Rock", durationMs = 180_000, bitrateKbps = 320, sampleRateHz = 44_100, bitDepth = 16,
            channels = 2, container = "flac", mimeType = "audio/flac", sizeBytes = 1234, artworkId = "c1",
            artworkUrl = "http://x/c1", starred = true, rating = 4, playCount = 7, replayGainTrackDb = -6.5,
            replayGainAlbumDb = -7.0, replayGainTrackPeak = 0.98, replayGainAlbumPeak = 0.99,
            musicBrainzId = "mb", path = "/a/b.flac", createdAt = 99L,
        )
        val entity = track.toEntity(generation = 5L)
        assertEquals(5L, entity.syncGeneration)
        assertEquals(track, entity.toDomain())
    }

    @Test
    fun `entities carry article-free sort keys`() {
        val artist = Artist("ar1", "s1", "The Strokes", sortName = null).toEntity(1)
        assertEquals("Strokes", artist.nameSortKey)
        val album = Album("a1", "s1", "The Dark Side of the Moon", artistName = "A Tribe Called Quest").toEntity(1)
        assertEquals("Dark Side of the Moon", album.nameSortKey)
        assertEquals("Tribe Called Quest", album.artistSortKey)
        val track = Track("t1", "s1", "A Day in the Life", artistName = null, albumName = "The White Album", durationMs = 1).toEntity(1)
        assertEquals("Day in the Life", track.titleSortKey)
        assertEquals("", track.artistSortKey)
        assertEquals("White Album", track.albumSortKey)
    }

    @Test
    fun `album and artist and playlist round trip`() {
        val album = Album("a1", "s1", "Album", "Album", "ar1", "Artist", 1999, "Jazz", 10, 3_000_000, "c", "u", true, 5, 3, 10L, 20L, false, "mb")
        assertEquals(album, album.toEntity(1).toDomain())
        val artist = Artist("ar1", "s1", "Artist", "Artist, The", 3, "c", "u", false, 0, "mb", "bio")
        assertEquals(artist, artist.toEntity(1).toDomain())
        val playlist = Playlist("p1", "s1", "Mix", "desc", "me", 12, 1000, "c", "u", true, 1L, 2L)
        assertEquals(playlist, playlist.toEntity(1).toDomain())
    }

    @Test
    fun `transcode profiles from room are never built-in`() {
        val profile = TranscodeProfile("custom.1", "Kitchen", AudioCodec.AAC, 192, 48_000, 16, builtIn = true)
        val restored = profile.toEntity().toDomain()
        assertFalse(restored.builtIn)
        assertEquals(profile.copy(builtIn = false), restored)
    }

    @Test
    fun `unknown codec falls back to original`() {
        val entity = TranscodeProfile("x", "X", AudioCodec.OPUS).toEntity().copy(codec = "WMA")
        assertEquals(AudioCodec.ORIGINAL, entity.toDomain().codec)
    }
}
