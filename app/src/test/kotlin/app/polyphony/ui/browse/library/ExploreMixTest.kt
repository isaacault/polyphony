package app.polyphony.ui.browse.library

import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.CatalogAlbum
import org.junit.Assert.assertEquals
import org.junit.Test

class ExploreMixTest {
    private fun new(t: String) = CatalogAlbum(key = t, releaseGroupId = t, title = t, artist = "A", year = null, albumType = null, artworkUrl = null,
                                              availability = Availability.Requestable)
    private fun yours(t: String) = Album(id = t, serverId = "s", name = t)

    @Test
    fun `albums you don't have and yours take turns`() {
        val out = ExploreViewModel.mix(listOf(new("n1"), new("n2"), new("n3")), listOf(yours("y1")))
        assertEquals(listOf("n1", "y1", "n2", "n3"), out.map { if (it is ExploreAlbum.New) it.album.title else (it as ExploreAlbum.Yours).album.name })
    }
}
