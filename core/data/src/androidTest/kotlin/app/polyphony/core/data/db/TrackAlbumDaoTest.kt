package app.polyphony.core.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.polyphony.core.data.mappers.toEntity
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.AlbumSort
import app.polyphony.core.domain.model.LibraryFilter
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.model.TrackSort
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the track and album DAOs against a real (in-memory) Room database on a device: upsert,
 * get, the raw sort queries, generation-based stale cleanup, and full-text search. Run with
 * `./gradlew :core:data:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class TrackAlbumDaoTest {
    private lateinit var db: PolyphonyDatabase
    private val sid = "s1"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PolyphonyDatabase::class.java)
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun track(id: String, title: String, artist: String? = null, album: String? = null, gen: Long = 1) =
        Track(id = id, serverId = sid, title = title, artistName = artist, albumName = album).toEntity(gen)

    private fun album(id: String, name: String, artist: String? = null, gen: Long = 1) =
        Album(id = id, serverId = sid, name = name, artistName = artist).toEntity(gen)

    @Test
    fun upsertAndGetAndCount() = runBlocking {
        val dao = db.trackDao()
        dao.upsertAll(listOf(track("t1", "Alpha"), track("t2", "Bravo")))
        assertEquals(2, dao.count(sid))
        assertEquals("Alpha", dao.get(sid, "t1")?.title)
        assertNull(dao.get(sid, "missing"))
    }

    @Test
    fun tracksSortByTitleAscending() = runBlocking {
        val dao = db.trackDao()
        dao.upsertAll(listOf(track("t1", "Cherry"), track("t2", "apple"), track("t3", "Banana")))
        val titles = dao.observeSorted(SortQueries.tracks(sid, TrackSort.TITLE, ascending = true)).first().map { it.title }
        assertEquals(listOf("apple", "Banana", "Cherry"), titles)
    }

    @Test
    fun albumsSortByArtistThenName() = runBlocking {
        val dao = db.albumDao()
        dao.upsertAll(
            listOf(
                album("a1", "Zebra", artist = "Beta"),
                album("a2", "Apple", artist = "Alpha"),
                album("a3", "Mango", artist = "Alpha"),
            ),
        )
        val order = dao.observeSorted(SortQueries.albums(sid, AlbumSort.ARTIST, ascending = true)).first().map { it.id }
        // Alpha before Beta; within Alpha, year then name (both year 0) -> Apple before Mango.
        assertEquals(listOf("a2", "a3", "a1"), order)
    }

    @Test
    fun deleteStaleRemovesOlderGenerations() = runBlocking {
        val dao = db.trackDao()
        dao.upsertAll(listOf(track("t1", "Keep", gen = 5), track("t2", "Drop", gen = 4)))
        dao.deleteStale(sid, generation = 5)
        assertEquals(1, dao.count(sid))
        assertEquals("Keep", dao.get(sid, "t1")?.title)
        assertNull(dao.get(sid, "t2"))
    }

    @Test
    fun fullTextSearchMatchesTitlePrefix() = runBlocking {
        val dao = db.trackDao()
        dao.upsertAll(listOf(track("t1", "Timberline"), track("t2", "Paper Moon"), track("t3", "Tidal")))
        val hits = dao.observeSorted(SearchQueries.tracksFts(sid, "ti*", LibraryFilter.NONE, limit = 10)).first().map { it.id }
        assertTrue("Timberline matches", hits.contains("t1"))
        assertTrue("Tidal matches", hits.contains("t3"))
        assertTrue("Paper Moon does not match 'ti*'", !hits.contains("t2"))
    }
}
