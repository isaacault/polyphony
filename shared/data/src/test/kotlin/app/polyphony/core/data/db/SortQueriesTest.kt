package app.polyphony.core.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import app.polyphony.core.domain.model.AlbumSort
import app.polyphony.core.domain.model.ArtistSort
import app.polyphony.core.domain.model.LEADING_ARTICLES
import app.polyphony.core.domain.model.LibraryFilter
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

    private fun SimpleSQLiteQuery.boundArgs(): List<Any?> {
        val args = ArrayList<Any?>()
        bindTo(object : SupportSQLiteProgramStub() {
            override fun bindString(index: Int, value: String) = record(args, index, value)
            override fun bindLong(index: Int, value: Long) = record(args, index, value)
        })
        return args
    }

    @Test
    fun `no filter leaves the queries exactly as they were`() {
        assertEquals(
            "SELECT * FROM tracks WHERE serverId = ? ORDER BY titleSortKey ASC, title COLLATE NOCASE ASC",
            SortQueries.tracks("s1", TrackSort.TITLE, ascending = true).sql,
        )
    }

    @Test
    fun `filters bind after the server id and before limit and offset`() {
        val filter = LibraryFilter(favouritesOnly = true, minRating = 3, genre = "Jazz", yearFrom = 1990, yearTo = 1999)
        val q = SortQueries.tracksSlice("s1", TrackSort.TITLE, ascending = true, limit = 50, offset = 100, filter = filter)
        assertTrue(
            q.sql.startsWith(
                "SELECT * FROM tracks WHERE serverId = ? AND starred = 1 AND rating >= ? AND genre = ? COLLATE NOCASE " +
                    "AND year > 0 AND year >= ? AND year <= ? ORDER BY",
            ),
        )
        assertEquals(listOf<Any?>("s1", 3L, "Jazz", 1990L, 1999L, 50L, 100L), q.boundArgs())
    }

    @Test
    fun `artists ignore what they cannot carry and downloads never reach SQL`() {
        val q = SortQueries.artists("s1", ArtistSort.NAME, LibraryFilter(minRating = 4, genre = "Jazz", yearTo = 2000, downloadedOnly = true))
        assertEquals("SELECT * FROM artists WHERE serverId = ? AND rating >= ? ORDER BY nameSortKey ASC", q.sql)
        assertEquals(listOf<Any?>("s1", 4L), q.boundArgs())
    }

    @Test
    fun `search binds the term, then the server, then the filter, then the limit`() {
        val fts = SearchQueries.tracksFts("s1", "blue*", LibraryFilter(minRating = 5), limit = 30)
        assertTrue(fts.sql.contains("WHERE tracks_fts MATCH ? AND tracks.serverId = ? AND tracks.rating >= ? ORDER BY"))
        assertEquals(listOf<Any?>("blue*", "s1", 5L, 30L), fts.boundArgs())
        val like = SearchQueries.albumsLike("s1", "%blu%", LibraryFilter(favouritesOnly = true), limit = 30)
        assertEquals(listOf<Any?>("s1", "%blu%", "%blu%", 30L), like.boundArgs())
        assertTrue(like.sql.contains(") AND starred = 1 ORDER BY"))
    }
}

/** Collects what a query binds, by 1-based position. */
private abstract class SupportSQLiteProgramStub : androidx.sqlite.db.SupportSQLiteProgram {
    fun record(args: ArrayList<Any?>, index: Int, value: Any?) {
        while (args.size < index) args += null
        args[index - 1] = value
    }
    override fun bindNull(index: Int) = Unit
    override fun bindDouble(index: Int, value: Double) = Unit
    override fun bindBlob(index: Int, value: ByteArray) = Unit
    override fun clearBindings() = Unit
    override fun close() = Unit
}
