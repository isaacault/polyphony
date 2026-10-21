package app.polyphony.core.data.db

import app.polyphony.core.domain.model.AlbumSort
import app.polyphony.core.domain.model.ArtistSort
import app.polyphony.core.domain.model.LEADING_ARTICLES
import app.polyphony.core.domain.model.TrackSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SortQueriesTest {

    @Test
    fun `slice keeps the list order and binds limit and offset`() {
        val full = SortQueries.tracks("s1", TrackSort.TITLE, ascending = true)
        val slice = SortQueries.tracksSlice("s1", TrackSort.TITLE, ascending = true, limit = 50, offset = 200)
        assertEquals(full.sql + " LIMIT ? OFFSET ?", slice.sql)
        assertEquals(3, slice.argCount)
    }

    @Test
    fun `sort keys read the column the list is sorted by`() {
        assertTrue(SortQueries.trackSortKeys("s1", TrackSort.ARTIST, true).sql.startsWith("SELECT substr(artistSortKey, 1, 1)"))
        assertTrue(SortQueries.trackSortKeys("s1", TrackSort.ALBUM, true).sql.startsWith("SELECT substr(albumSortKey, 1, 1)"))
        assertTrue(SortQueries.trackSortKeys("s1", TrackSort.DURATION, true).sql.startsWith("SELECT substr(titleSortKey, 1, 1)"))
    }

    @Test
    fun `A-Z sorts read the stored article-free keys`() {
        assertTrue(SortQueries.artists("s1", ArtistSort.NAME).sql.endsWith("ORDER BY nameSortKey ASC"))
        assertTrue(SortQueries.albums("s1", AlbumSort.ARTIST, ascending = true).sql.contains("ORDER BY artistSortKey ASC"))
        assertTrue(SortQueries.tracks("s1", TrackSort.TITLE, ascending = true).sql.contains("ORDER BY titleSortKey ASC"))
    }

    @Test
    fun `the SQL twin strips the same articles as sortKey`() {
        val sql = SortQueries.sortable("name")
        LEADING_ARTICLES.forEach { article ->
            assertTrue(sql.contains("LIKE '$article%' AND length(trim(name)) > ${article.length} THEN substr(trim(name), ${article.length + 1})"))
        }
        assertTrue(sql.endsWith("ELSE trim(name) END)"))
    }

    @Test
    fun `sort keys and the paged list share one ORDER BY`() {
        val list = SortQueries.tracks("s1", TrackSort.ARTIST, ascending = false).sql.substringAfter("ORDER BY")
        val keys = SortQueries.trackSortKeys("s1", TrackSort.ARTIST, ascending = false).sql.substringAfter("ORDER BY")
        assertEquals(list, keys)
    }

    @Test
    fun `descending flips only the primary column`() {
        val q = SortQueries.tracks("s1", TrackSort.TITLE, ascending = false)
        assertTrue(q.sql.contains("ORDER BY titleSortKey DESC, title COLLATE NOCASE ASC"))
    }
}
