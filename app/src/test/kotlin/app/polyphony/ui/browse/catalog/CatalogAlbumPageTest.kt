package app.polyphony.ui.browse.catalog

import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.CatalogAlbum
import app.polyphony.core.domain.model.CatalogAlbumDetail
import app.polyphony.core.domain.model.CatalogAlbumTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogAlbumPageTest {

    private val detail = CatalogAlbumDetail(
        album = CatalogAlbum("mb:rg:th", "th", "Tomorrow's Harvest", "Boards of Canada", 2013, "Album", null, Availability.Requestable),
        tracks = listOf(
            CatalogAlbumTrack(2, 1, "Reach for the Dead", null),
            CatalogAlbumTrack(1, 2, "Bonus", 60_000),
            CatalogAlbumTrack(1, 1, "Gemini", 172_000),
        ),
    )

    @Test
    fun `a request made on the page wins over what the server said`() {
        assertEquals(Availability.Requestable, CatalogAlbumPage.availability(detail, emptyMap(), "th"))
        val requested = mapOf("th" to Availability.Requested(9, "requested"))
        assertEquals(Availability.Requested(9, "requested"), CatalogAlbumPage.availability(detail, requested, "th"))
        assertEquals(Availability.Requestable, CatalogAlbumPage.availability(null, emptyMap(), "th"))
    }

    @Test
    fun `tracks are split by disc, in order`() {
        val discs = CatalogAlbumPage.discs(detail.tracks)
        assertEquals(listOf(1, 2), discs.map { it.number })
        assertEquals(listOf("Gemini", "Reach for the Dead"), discs[0].tracks.map { it.title })
    }
}
