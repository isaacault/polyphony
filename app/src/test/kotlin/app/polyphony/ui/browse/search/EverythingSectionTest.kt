package app.polyphony.ui.browse.search

import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.CatalogAlbum
import app.polyphony.core.domain.model.CatalogArtist
import app.polyphony.core.domain.model.CatalogResults
import app.polyphony.core.domain.model.CatalogTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EverythingSectionTest {

    private fun album(rg: String?, availability: Availability) =
        CatalogAlbum("k-$rg", rg, "Album $rg", "Artist", null, null, null, availability)

    private fun track(rg: String?, availability: Availability) =
        CatalogTrack("t-$rg", "Track $rg", "Artist", null, "Album $rg", rg, availability)

    private val results = CatalogResults(
        albums = listOf(
            album("owned", Availability.InLibrary("nd-1")),
            album("half", Availability.Partial(9, 23, "nd-2")),
            album("new", Availability.Requestable),
            album("busy", Availability.Requested(17, "downloading")),
        ),
        tracks = listOf(
            track("owned", Availability.InLibrary("nd-1")),
            track("new", Availability.Requestable),
        ),
    )

    @Test
    fun `library hits stay in the library sections`() {
        assertEquals(listOf("new", "busy"), EverythingSection.albums(results, emptyMap()).map { it.releaseGroupId })
        assertEquals(listOf("new"), EverythingSection.tracks(results, emptyMap()).map { it.albumReleaseGroupId })
    }

    @Test
    fun `a request made here marks the album and its tracks`() {
        val requested = mapOf("new" to Availability.Requested(21, "requested"))
        assertEquals(Availability.Requested(21, "requested"), EverythingSection.albums(results, requested).first().availability)
        assertEquals(Availability.Requested(21, "requested"), EverythingSection.tracks(results, requested).single().availability)
    }

    @Test
    fun `an album found to be in the library leaves Everything`() {
        val requested = mapOf("new" to Availability.InLibrary("nd-9"))
        assertEquals(listOf("busy"), EverythingSection.albums(results, requested).map { it.releaseGroupId })
        assertTrue(EverythingSection.tracks(results, requested).isEmpty())
    }

    @Test
    fun `only requestable hits with a release group get a Request button`() {
        assertTrue(EverythingSection.canRequest(Availability.Requestable, "rg"))
        assertFalse(EverythingSection.canRequest(Availability.Requestable, null))
        assertTrue("on the server: the request adds it", EverythingSection.canRequest(Availability.OnServer("s-1"), "rg"))
        assertFalse(EverythingSection.canRequest(Availability.Requested(1, "searching"), "rg"))
        assertFalse(EverythingSection.canRequest(Availability.CatalogOnly, "rg"))
    }

    @Test
    fun `no results is an empty section`() {
        assertTrue(EverythingSection.albums(null, emptyMap()).isEmpty())
    }

    @Test
    fun `a partly owned album is marked on its library row`() {
        assertEquals(mapOf("nd-2" to Availability.Partial(9, 23, "nd-2")), EverythingSection.partials(results))
        assertEquals(emptyMap<String, Availability.Partial>(), EverythingSection.partials(null))
    }

    @Test
    fun `a type chip shows one section, with more room in it`() {
        assertTrue(SearchKind.ALL.shows(SearchKind.ALBUMS))
        assertTrue(SearchKind.ALBUMS.shows(SearchKind.ALBUMS))
        assertFalse(SearchKind.ALBUMS.shows(SearchKind.SONGS))
        assertTrue(SearchKind.SONGS.localLimit > SearchKind.ALL.localLimit)
    }

    @Test
    fun `only catalogue artists with an MBID join the Artists section`() {
        val artists = CatalogResults(
            artists = listOf(
                CatalogArtist("mb:artist:boc", "boc", "Boards of Canada", null, Availability.InLibrary(null)),
                CatalogArtist("mb:artist:ae", "ae", "Autechre", null, Availability.CatalogOnly),
                CatalogArtist("cat:x", null, "No Id", null, Availability.CatalogOnly),
            ),
        )
        assertEquals(listOf("Autechre"), EverythingSection.artists(artists).map { it.name })
    }

    @Test
    fun `what the query names comes first in a section`() {
        val names = listOf("Peach Pit Live", "The Peach Pit Tapes", "Pitbull", "Peach Pit", "Peaches")
        assertEquals(
            listOf("Peach Pit", "Peach Pit Live", "The Peach Pit Tapes", "Pitbull", "Peaches"),
            SearchRanking.rank(names, "peach pit") { it },
        )
        assertEquals(listOf("Pitbull", "Peach Pit Live"), SearchRanking.rank(listOf("Peach Pit Live", "Pitbull"), "pit") { it })
        assertEquals(names, SearchRanking.rank(names, "  ") { it })
    }

    @Test
    fun `an exact name is what was typed, case, punctuation and The aside`() {
        assertTrue(SearchRanking.isExact("The Strokes", "strokes"))
        assertTrue(SearchRanking.isExact("Peach Pit", "peach  pit"))
        assertTrue(SearchRanking.isExact("AC/DC", "ac dc"))
        assertFalse(SearchRanking.isExact("Peach Pit Live", "peach pit"))
        assertFalse(SearchRanking.isExact("Anything", "  "))
    }
}
