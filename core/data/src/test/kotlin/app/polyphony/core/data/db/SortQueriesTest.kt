package app.polyphony.core.data.db

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
        assertTrue(SortQueries.trackSortKeys("s1", TrackSort.ARTIST, true).sql.startsWith("SELECT substr(trim(artistName), 1, 1)"))
        assertTrue(SortQueries.trackSortKeys("s1", TrackSort.ALBUM, true).sql.startsWith("SELECT substr(trim(albumName), 1, 1)"))
        assertTrue(SortQueries.trackSortKeys("s1", TrackSort.DURATION, true).sql.startsWith("SELECT substr(trim(title), 1, 1)"))
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
        assertTrue(q.sql.contains("ORDER BY title COLLATE NOCASE DESC, title COLLATE NOCASE ASC"))
    }
}
