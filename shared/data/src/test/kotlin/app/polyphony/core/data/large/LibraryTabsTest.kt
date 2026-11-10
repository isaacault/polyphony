package app.polyphony.core.data.large

import androidx.paging.PagingSource
import app.polyphony.core.data.db.SortQueries
import app.polyphony.core.data.db.entity.AlbumEntity
import app.polyphony.core.data.db.entity.TrackEntity
import app.polyphony.core.domain.model.AlbumSort
import app.polyphony.core.domain.model.ArtistSort
import app.polyphony.core.domain.model.LetterIndex
import app.polyphony.core.domain.model.LibraryFilter
import app.polyphony.core.domain.model.TrackSort
import app.polyphony.core.domain.model.scrollerLetter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The queries behind the Library tabs on the 60,000-track fixture: Artists and Albums in every
 * sort, Songs paged from anywhere in the list, the alphabet rail's index and the song counts.
 */
class LibraryTabsTest {
    private val db = SyncedLibrary.db
    private val sid = SyncedLibrary.serverId
    private val library = SyncedLibrary.library

    @Test
    fun `artists A-Z file The and A names under their next word`() {
        val artists = Timings.measure("artists tab: NAME (2k artists)") {
            db.artistDao().observeSorted(SortQueries.artists(sid, ArtistSort.NAME)).first()
        }
        assertEquals(library.artists.size, artists.size)
        assertSorted("artists by name", artists.map { it.nameSortKey }, NOCASE)
        assertFalse(artists.any { it.nameSortKey.startsWith("The ") })
    }

    @Test
    fun `every artist sort lists every artist`() {
        for (sort in ArtistSort.entries) {
            val artists = Timings.measure("artists tab: $sort") { db.artistDao().observeSorted(SortQueries.artists(sid, sort)).first() }
            assertEquals(sort.name, library.artists.size, artists.size)
        }
    }

    @Test
    fun `albums sort both ways by every column`() {
        for (sort in AlbumSort.entries) {
            for (ascending in listOf(true, false)) {
                val albums = Timings.measure("albums tab: $sort ${if (ascending) "natural" else "reversed"} (5k albums)") {
                    db.albumDao().observeSorted(SortQueries.albums(sid, sort, ascending)).first()
                }
                assertEquals(library.albums.size, albums.size)
                val label = "$sort ascending=$ascending"
                when (sort) {
                    AlbumSort.NAME -> assertSorted(label, albums.map { it.nameSortKey }, NOCASE.directed(ascending))
                    AlbumSort.ARTIST -> assertSorted(label, albums.map { it.artistSortKey }, NOCASE.directed(ascending))
                    else -> assertSorted(label, albums.map { numericKey(sort, it) }, naturalOrder<Long>().directed(!ascending))
                }
            }
        }
    }

    private fun numericKey(sort: AlbumSort, a: AlbumEntity): Long = when (sort) {
        AlbumSort.YEAR -> (a.year ?: 0).toLong()
        AlbumSort.RECENTLY_ADDED -> a.createdAt
        AlbumSort.RECENTLY_PLAYED -> a.lastPlayedAt ?: 0
        AlbumSort.MOST_PLAYED -> a.playCount
        AlbumSort.RATING -> a.rating.toLong()
        AlbumSort.DURATION -> a.durationMs
        else -> error("not numeric: $sort")
    }

    private fun <T> Comparator<T>.directed(ascending: Boolean): Comparator<T> = if (ascending) this else reversed()

    @Test
    fun `songs load a page anywhere in the list, as the alphabet rail jumps`() {
        for (sort in TrackSort.entries) {
            val query = SortQueries.tracks(sid, sort, ascending = true)
            // A fresh source each time, as after an invalidation: its first load also counts the rows.
            val page = Timings.measure("songs tab: page of 100 at row 30,000, $sort") {
                db.trackDao().pagingSorted(query).load(PagingSource.LoadParams.Refresh(30_000, 100, true)) as PagingSource.LoadResult.Page<Int, TrackEntity>
            }
            assertEquals(100, page.data.size)
            assertEquals(30_000, page.itemsBefore)
            assertEquals(60_000 - 30_100, page.itemsAfter)
            val slice = runBlocking { db.trackDao().listSorted(SortQueries.tracksSlice(sid, sort, true, limit = 100, offset = 30_000)) }
            assertEquals(sort.name, slice.map { it.id }, page.data.map { it.id })
        }
    }

    @Test
    fun `the A-Z index points at the first song of each letter`() {
        for (sort in listOf(TrackSort.TITLE, TrackSort.ARTIST, TrackSort.ALBUM)) {
            val keys = Timings.measure("songs tab: A-Z index, $sort (60k keys)") {
                db.trackDao().observeSortKeys(SortQueries.trackSortKeys(sid, sort, true)).first()
            }
            val index = LetterIndex.of(keys.map { it.k })
            assertEquals(60_000, index.size)
            for (letter in listOf('B', 'H', 'M', 'S', 'W')) {
                val at = index.firstIndex.getValue(letter)
                val rows = runBlocking { db.trackDao().listSorted(SortQueries.tracksSlice(sid, sort, true, limit = 2, offset = at - 1)) }
                val key = { i: Int ->
                    when (sort) {
                        TrackSort.ARTIST -> rows[i].artistSortKey
                        TrackSort.ALBUM -> rows[i].albumSortKey
                        else -> rows[i].titleSortKey
                    }
                }
                assertNotEquals("$sort $letter", letter, scrollerLetter(key(0)))
                assertEquals("$sort $letter", letter, scrollerLetter(key(1)))
            }
        }
    }

    @Test
    fun `song counts follow the filter`() {
        fun count(filter: LibraryFilter) = Timings.measure("songs tab: count, $filter") {
            db.trackDao().observeCountOf(SortQueries.trackCount(sid, filter)).first()
        }
        assertEquals(60_000, count(LibraryFilter.NONE))
        assertEquals(library.tracks.count { it.genre.equals("Rock", ignoreCase = true) }, count(LibraryFilter(genre = "Rock")))
        assertEquals(library.tracks.count { it.starred }, count(LibraryFilter(favouritesOnly = true)))
        assertEquals(library.tracks.count { (it.year ?: 0) in 1990..1999 }, count(LibraryFilter(yearFrom = 1990, yearTo = 1999)))
    }

    @Test
    fun `a filtered songs list pages in order`() {
        val filter = LibraryFilter(genre = "jazz")
        val expected = library.tracks.count { it.genre.equals("jazz", ignoreCase = true) }
        val rows = Timings.measure("songs tab: first page, genre filter (TITLE)") {
            db.trackDao().listSorted(SortQueries.tracksSlice(sid, TrackSort.TITLE, true, limit = 100, offset = 0, filter = filter))
        }
        assertEquals(minOf(100, expected), rows.size)
        assertSorted("jazz by title", rows.map { it.titleSortKey }, NOCASE)
        rows.forEach { assertEquals("jazz", it.genre?.lowercase()) }
    }
}
