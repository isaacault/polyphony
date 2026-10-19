package app.polyphony.provider.subsonic

import app.polyphony.core.domain.model.AlbumListType
import app.polyphony.core.domain.transcode.AudioCodec
import app.polyphony.provider.subsonic.internal.parseIsoMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubsonicMapperTest {

    @Test
    fun `album list types map to subsonic names`() {
        val expected = mapOf(
            AlbumListType.RECENTLY_ADDED to "newest",
            AlbumListType.RECENTLY_PLAYED to "recent",
            AlbumListType.MOST_PLAYED to "frequent",
            AlbumListType.RANDOM to "random",
            AlbumListType.STARRED to "starred",
            AlbumListType.HIGHEST_RATED to "highest",
            AlbumListType.ALPHABETICAL_BY_NAME to "alphabeticalByName",
            AlbumListType.ALPHABETICAL_BY_ARTIST to "alphabeticalByArtist",
            AlbumListType.BY_YEAR to "byYear",
            AlbumListType.BY_GENRE to "byGenre",
        )
        AlbumListType.entries.forEach { type ->
            assertEquals(type.name, expected.getValue(type), SubsonicMapper.albumListType(type))
        }
    }

    @Test
    fun `codec format params`() {
        assertEquals("raw", SubsonicMapper.formatParam(AudioCodec.ORIGINAL))
        assertEquals("opus", SubsonicMapper.formatParam(AudioCodec.OPUS))
        assertEquals("mp3", SubsonicMapper.formatParam(AudioCodec.MP3))
        assertEquals("aac", SubsonicMapper.formatParam(AudioCodec.AAC))
        assertEquals("ogg", SubsonicMapper.formatParam(AudioCodec.VORBIS))
        assertEquals("flac", SubsonicMapper.formatParam(AudioCodec.FLAC))
    }

    @Test
    fun `iso dates parse in the shapes servers emit`() {
        assertEquals(1_700_000_000_000L, parseIsoMillis("2023-11-14T22:13:20Z"))
        assertEquals(1_700_000_000_000L, parseIsoMillis("2023-11-14T22:13:20.000Z"))
        assertEquals(1_700_000_000_000L, parseIsoMillis("2023-11-14T22:13:20"))
        assertEquals(1_700_000_000_000L, parseIsoMillis("2023-11-15T00:13:20+02:00"))
        assertEquals(1_700_000_000_000L, parseIsoMillis("2023-11-15T00:13:20+0200"))
        assertEquals(1_700_000_000_000L, parseIsoMillis("2023-11-14T22:13:20.123456789Z")?.let { it / 1000 * 1000 })
        assertNull(parseIsoMillis(null))
        assertNull(parseIsoMillis(""))
        assertNull(parseIsoMillis("yesterday"))
    }
}
