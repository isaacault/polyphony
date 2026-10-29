package app.polyphony.ui.browse.library

import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Artist
import org.junit.Assert.assertEquals
import org.junit.Test

class SinglesFilterTest {
    private fun artist(id: String) = Artist(id = id, serverId = "s", name = id)
    private fun album(id: String, artist: String) = Album(id = id, serverId = "s", name = id, artistId = artist)

    @Test
    fun `an artist you only have single songs by is left out`() {
        val artists = listOf(artist("peach"), artist("alabaster"), artist("guest"))
        val albums = listOf(album("bsn", "peach"), album("cy", "alabaster"))
        val shown = SinglesFilter.artistsWithAlbums(artists, albums, singleIds = setOf("cy"))
        assertEquals(listOf("peach", "guest"), shown.map { it.id })
    }
}
