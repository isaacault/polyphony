package app.polyphony.ui.browse.catalog

import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.CatalogAlbum
import app.polyphony.core.domain.model.CatalogArtist
import app.polyphony.core.domain.model.CatalogArtistPage
import app.polyphony.core.domain.model.CatalogTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogArtistPageLogicTest {

    private fun track(rg: String?) = CatalogTrack("t-$rg", "Song $rg", "BoC", null, "Album $rg", rg, Availability.Requestable)
    private fun album(rg: String, year: Int?, art: String?) =
        CatalogAlbum("mb:rg:$rg", rg, "Album $rg", "BoC", year, "Album", art, Availability.Requestable)

    @Test
    fun `a request made on the page marks every top song from that album`() {
        val requested = mapOf("a" to Availability.Requested(3, "requested"))
        val out = CatalogArtistPageLogic.topTracks(listOf(track("a"), track("b"), track(null)), requested)
        assertEquals(listOf(Availability.Requested(3, "requested"), Availability.Requestable, Availability.Requestable), out.map { it.availability })
    }

    @Test
    fun `a song requested on its own marks that song only`() {
        val requested = mapOf("rec-1" to Availability.Requested(4, "requested"))
        val out = CatalogArtistPageLogic.topTracks(listOf(track("a").copy(recordingId = "rec-1"), track("a")), requested)
        assertEquals(listOf(Availability.Requested(4, "requested"), Availability.Requestable), out.map { it.availability })
    }

    @Test
    fun `the hero is the artist's picture, else the newest album cover`() {
        val albums = listOf(album("old", 1998, "old.jpg"), album("new", 2013, "new.jpg"), album("none", 2020, null))
        val page = CatalogArtistPage(CatalogArtist("k", "boc", "BoC", "boc.jpg", Availability.CatalogOnly), null, null, null, emptyList(), emptyList())
        assertEquals("boc.jpg", CatalogArtistPageLogic.heroArtwork(page, albums))
        assertEquals("new.jpg", CatalogArtistPageLogic.heroArtwork(null, albums))
        assertNull(CatalogArtistPageLogic.heroArtwork(null, emptyList()))
    }

    @Test
    fun `names match regardless of case and punctuation`() {
        assertTrue(CatalogArtistPageLogic.sameName("AC/DC", "ac dc"))
        assertTrue(CatalogArtistPageLogic.sameName("Sigur Rós", "sigur rós"))
    }
}
