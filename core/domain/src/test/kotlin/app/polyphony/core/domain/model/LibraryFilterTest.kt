package app.polyphony.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFilterTest {

    private val track = Track("t", "s", "Song", genre = "Jazz", year = 1994, starred = false, rating = 4)

    @Test
    fun `the default filter narrows nothing`() {
        assertFalse(LibraryFilter.NONE.isActive)
        assertTrue(LibraryFilter.NONE.activeFields.isEmpty())
        assertTrue(LibraryFilter.NONE.matches(track))
    }

    @Test
    fun `rating is a minimum`() {
        assertTrue(LibraryFilter(minRating = 4).matches(track))
        assertTrue(LibraryFilter(minRating = 3).matches(track))
        assertFalse(LibraryFilter(minRating = 5).matches(track))
    }

    @Test
    fun `favourites, genre and year all have to hold`() {
        assertFalse(LibraryFilter(favouritesOnly = true).matches(track))
        assertTrue(LibraryFilter(genre = "jazz").matches(track))
        assertFalse(LibraryFilter(genre = "Rock").matches(track))
        assertTrue(LibraryFilter(yearFrom = 1990, yearTo = 1999).matches(track))
        assertTrue(LibraryFilter(yearFrom = 1994).matches(track))
        assertFalse(LibraryFilter(yearTo = 1993).matches(track))
        assertFalse("an unknown year is outside any range", LibraryFilter(yearFrom = 1900).matches(track.copy(year = null)))
        assertFalse(LibraryFilter(minRating = 3, genre = "Rock").matches(track))
    }

    @Test
    fun `restricting drops what a kind cannot carry`() {
        val filter = LibraryFilter(favouritesOnly = true, minRating = 3, downloadedOnly = true, genre = "Jazz", yearFrom = 1990)
        assertEquals(LibraryFilter(favouritesOnly = true, minRating = 3, downloadedOnly = true), filter.restrictedTo(ItemKind.ARTIST))
        assertEquals(LibraryFilter(downloadedOnly = true), filter.restrictedTo(ItemKind.PLAYLIST))
        assertEquals(filter, filter.restrictedTo(ItemKind.TRACK))
    }

    @Test
    fun `a field a kind cannot carry excludes that kind from mixed results`() {
        assertTrue(LibraryFilter(minRating = 4).excludesAll(ItemKind.PLAYLIST))
        assertFalse(LibraryFilter(downloadedOnly = true).excludesAll(ItemKind.PLAYLIST))
        assertTrue(LibraryFilter(genre = "Jazz").excludesAll(ItemKind.ARTIST))
        assertFalse(LibraryFilter(minRating = 4).excludesAll(ItemKind.ARTIST))
        assertFalse(LibraryFilter.NONE.excludesAll(ItemKind.PLAYLIST))
    }
}
