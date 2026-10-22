package app.polyphony.ui.browse.catalog

import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.CatalogAlbum
import org.junit.Assert.assertEquals
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
}
