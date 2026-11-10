package app.polyphony.core.data.large

import app.polyphony.core.data.db.SearchQueries
import app.polyphony.core.data.db.entity.TrackEntity
import app.polyphony.core.data.search.FtsQuery
import app.polyphony.core.domain.model.LibraryFilter
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local search on the 60,000-track fixture: prefix FTS on every word of title, artist and album,
 * accents folded, the LIKE fallback for a fragment from inside a word, and filters applied in SQL.
 */
class LargeSearchTest {
    private val db = SyncedLibrary.db
    private val sid = SyncedLibrary.serverId
    private val library = SyncedLibrary.library
    private val word = Regex("[\\p{L}\\p{N}]+")

    private fun words(t: TrackEntity) = listOfNotNull(t.title, t.artistName, t.albumName).flatMap { field -> word.findAll(field.lowercase()).map { it.value }.toList() }

    private fun tracks(text: String, limit: Int = 50, filter: LibraryFilter = LibraryFilter.NONE) =
        Timings.measure("search: tracks \"$text\"${if (filter.isActive) " $filter" else ""}, limit $limit") {
            db.trackDao().observeSorted(SearchQueries.tracksFts(sid, FtsQuery.match(text)!!, filter, limit)).first()
        }

    @Test
    fun `a word prefix finds songs by title, artist or album, sorted by title`() {
        val hits = tracks("heart")
        assertEquals(50, hits.size)
        hits.forEach { t -> assertTrue(t.title, words(t).any { it.startsWith("heart") }) }
        assertSorted("search results by title", hits.map { it.title }, NOCASE)
        val all = tracks("heart", limit = 100_000)
        val expected = library.tracks.count { t ->
            listOfNotNull(t.title, t.artistName, t.albumName).any { f -> word.findAll(f.lowercase()).any { it.value.startsWith("heart") } }
        }
        assertEquals(expected, all.size)
    }

    @Test
    fun `every word of the query must match`() {
        val hits = tracks("golden heart", limit = 1_000)
        assertTrue(hits.isNotEmpty())
        hits.forEach { t ->
            val w = words(t)
            assertTrue(t.title, w.any { it.startsWith("golden") } && w.any { it.startsWith("heart") })
        }
    }

    @Test
    fun `the first keystroke is a one-letter prefix over most of the library`() {
        val hits = tracks("s")
        assertEquals(50, hits.size)
    }

    @Test
    fun `accents fold, and non-Latin names are found`() {
        val zoe = tracks("zoe", limit = 1_000)
        assertTrue(zoe.isNotEmpty())
        zoe.forEach { t -> assertTrue(t.title, words(t).any { it.startsWith("zoe") || it.startsWith("zoë") }) }
        val tokyo = Timings.measure("search: artists \"東京\"") {
            db.artistDao().observeSorted(SearchQueries.artistsFts(sid, FtsQuery.match("東京")!!, LibraryFilter.NONE, 50)).first()
        }
        assertEquals(library.artists.count { it.name.startsWith("東京") }, tokyo.size)
    }

    @Test
    fun `a fragment from inside a word falls back to LIKE`() {
        assertEquals(0, tracks("eart").size)
        val like = Timings.measure("search: tracks LIKE \"%eart%\" (fallback), limit 50") {
            db.trackDao().observeSorted(SearchQueries.tracksLike(sid, FtsQuery.likePattern("eart"), LibraryFilter.NONE, 50)).first()
        }
        assertEquals(50, like.size)
        like.forEach { t -> assertTrue(t.title, listOfNotNull(t.title, t.artistName, t.albumName).any { it.contains("eart", ignoreCase = true) }) }
    }

    @Test
    fun `albums and artists are searched by prefix too`() {
        val albums = Timings.measure("search: albums \"silver\"") {
            db.albumDao().observeSorted(SearchQueries.albumsFts(sid, FtsQuery.match("silver")!!, LibraryFilter.NONE, 50)).first()
        }
        assertTrue(albums.isNotEmpty())
        albums.forEach { a -> assertTrue(a.name, "${a.name} ${a.artistName}".contains("silver", ignoreCase = true)) }
        val artists = Timings.measure("search: artists \"the\"") {
            db.artistDao().observeSorted(SearchQueries.artistsFts(sid, FtsQuery.match("the")!!, LibraryFilter.NONE, 50)).first()
        }
        assertEquals(50, artists.size)
    }

    @Test
    fun `a filter narrows the search in SQL, so the limit still fills`() {
        val hits = tracks("heart", filter = LibraryFilter(genre = "Rock"))
        assertTrue(hits.isNotEmpty())
        hits.forEach { assertEquals("rock", it.genre?.lowercase()) }
        val starred = tracks("the", filter = LibraryFilter(favouritesOnly = true))
        starred.forEach { assertTrue(it.starred) }
    }
}
