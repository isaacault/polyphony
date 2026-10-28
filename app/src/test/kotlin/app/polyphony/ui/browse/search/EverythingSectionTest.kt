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
        assertTrue("only single songs of it: the album can be requested", EverythingSection.canRequest(Availability.Partial(1, 10, "nd", requestable = true), "rg"))
        assertFalse(EverythingSection.canRequest(Availability.Partial(9, 10, "nd"), "rg"))
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

    @Test
    fun `the server's best hit leads, the rest keep their order`() {
        val (lead, rest) = TopHit.byKey(listOf("a", "b", "c"), "b") { it }
        assertEquals(listOf("b"), lead)
        assertEquals(listOf("a", "c"), rest)
        assertEquals(emptyList<String>() to listOf("a"), TopHit.byKey(listOf("a"), null) { it })
        assertEquals(emptyList<String>() to listOf("a"), TopHit.byKey(listOf("a"), "gone") { it })
    }

    @Test
    fun `one of yours whose name starts as typed leads`() {
        val (lead, rest) = TopHit.startingWith(listOf("Ed Maverick", "Maverick Sabre", "Mavis"), "maverick") { it }
        assertEquals(listOf("Maverick Sabre"), lead)
        assertEquals(listOf("Ed Maverick", "Mavis"), rest)
        assertEquals("a leading The aside", listOf("The Weeknd"), TopHit.startingWith(listOf("The Weeknd"), "weeknd") { it }.first)
    }

    @Test
    fun `top results blend kinds, artists and popular things first, like Spotify's`() {
        fun cat(name: String) = CatalogArtist(key = "mb:artist:$name", musicBrainzId = name, name = name, artworkUrl = null,
                                              availability = Availability.CatalogOnly)
        val mySong = app.polyphony.core.domain.model.Track(id = "t1", serverId = "s", title = "Miss Me (feat. Lil Wayne)", artistName = "Drake")
        val myAlbum = app.polyphony.core.domain.model.Album(id = "a1", serverId = "s", name = "So Far Gone", artistName = "Drake")
        val myArtist = app.polyphony.core.domain.model.Artist(id = "ar1", serverId = "s", name = "Lil Baby")
        val items = TopBlend.build(
            "lil", listOf(myArtist), listOf(myAlbum), emptyList(), listOf(mySong),
            listOf(cat("Lil Peep"), cat("Lil Wayne"), cat("Lily Allen")), emptyList(), emptyList(),
            popularity = mapOf("mb:artist:Lil Peep" to 6.5f, "mb:artist:Lil Wayne" to 6.8f, "mb:artist:Lily Allen" to 6.0f),
            popularityByName = mapOf("lil baby" to 6.2f), bestKey = null,
        )
        val names = items.map {
            when (it) {
                is TopItem.CatArtist -> it.artist.name
                is TopItem.LibArtist -> it.artist.name
                is TopItem.LibTrack -> it.track.title
                is TopItem.LibAlbum -> it.album.name
                else -> "?"
            }
        }
        assertEquals(listOf("Lil Wayne", "Lil Baby", "Lil Peep", "Lily Allen", "Miss Me (feat. Lil Wayne)"), names)
        assertTrue("an album that doesn't match isn't a top result", "So Far Gone" !in names)
    }
}
