package app.polyphony.ui.browse.catalog

import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.CatalogAlbum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscographyTest {

    private fun album(rg: String, type: String?, year: Int?, availability: Availability = Availability.Requestable) =
        CatalogAlbum("mb:rg:$rg", rg, "Album $rg", "Artist", year, type, null, availability)

    private val albums = listOf(
        album("old", "Album", 1998),
        album("single", "Single", 2021),
        album("owned", "Album", 2005, Availability.InLibrary("nd-1")),
        album("half", "Album", 2010, Availability.Partial(9, 23, "nd-2")),
        album("new", "Album", 2019),
        album("ep", "EP", 2000),
        album("live", "Live", null),
    )

    @Test
    fun `missing releases are grouped Albums, EPs, Singles, then the rest, newest first`() {
        val groups = Discography.missing(albums, emptyMap())
        assertEquals(listOf(ReleaseType.ALBUM, ReleaseType.EP, ReleaseType.SINGLE, ReleaseType.OTHER), groups.map { it.type })
        assertEquals(listOf("new", "old"), groups[0].albums.map { it.releaseGroupId })
    }

    @Test
    fun `library albums are left out, and a request made here moves one out too`() {
        val requested = mapOf("old" to Availability.InLibrary("nd-9"), "new" to Availability.Requested(5, "requested"))
        val albumsGroup = Discography.missing(albums, requested).first { it.type == ReleaseType.ALBUM }
        assertEquals(listOf("new"), albumsGroup.albums.map { it.releaseGroupId })
        assertEquals(Availability.Requested(5, "requested"), albumsGroup.albums.single().availability)
    }

    @Test
    fun `partly owned albums are keyed by their library album`() {
        assertEquals(mapOf("nd-2" to Availability.Partial(9, 23, "nd-2")), Discography.partials(albums))
    }

    @Test
    fun `release types match case-insensitively`() {
        assertEquals(ReleaseType.EP, Discography.typeOf("ep"))
        assertEquals(ReleaseType.OTHER, Discography.typeOf(null))
    }

    private fun owned(id: String, year: Int?) = Album(id = id, serverId = "s", name = "Owned $id", year = year)

    @Test
    fun `your albums and the catalogue's merge into one list per release type`() {
        val library = listOf(owned("nd-1", 2005), owned("nd-2", 2010), owned("nd-live", 2012))
        val groups = Discography.merged(library, albums, emptyMap())

        assertEquals(listOf(ReleaseType.ALBUM, ReleaseType.EP, ReleaseType.SINGLE, ReleaseType.OTHER), groups.map { it.type })
        // Newest first; your copy stands in for the catalogue's in_library and partial releases,
        // and a library album the catalogue doesn't list joins the Albums.
        assertEquals(listOf("m-mb:rg:new", "o-nd-live", "o-nd-2", "o-nd-1", "m-mb:rg:old"), groups[0].entries.map { it.key })
    }

    @Test
    fun `a release in the library under another artist stays a catalogue card`() {
        val elsewhere = listOf(album("x", "Album", 2001, Availability.InLibrary("nd-other")))
        val entry = Discography.merged(emptyList(), elsewhere, emptyMap()).single().entries.single()
        assertTrue(entry is DiscographyEntry.Missing)
    }

    @Test
    fun `no catalogue, no merged list`() {
        assertTrue(Discography.merged(listOf(owned("nd-1", 2005)), emptyList(), emptyMap()).isEmpty())
    }

    @Test
    fun `a second release matched to the same library album is offered, not dropped`() {
        val live = owned("nd-live", 2014)
        val catalog = listOf(
            album("zero-pct", "Album", 2014, Availability.InLibrary("nd-live")),
            album("zero", "Album", 2013, Availability.InLibrary("nd-live")),
        )
        val entries = Discography.merged(listOf(live), catalog, emptyMap()).single().entries
        assertEquals(listOf("o-nd-live", "m-mb:rg:zero"), entries.map { it.key })
        assertEquals(Availability.Requestable, (entries[1] as DiscographyEntry.Missing).album.availability)
    }
}
