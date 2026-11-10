package app.polyphony.core.data.large

import app.polyphony.core.data.db.SortQueries
import app.polyphony.core.data.offline.OfflineFilter
import app.polyphony.core.domain.model.TrackSort
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home's shelves and the download rules' track lists on the 60,000-track fixture with a full
 * listening history: what each returns, and how long it takes.
 */
class HomeAndRulesQueriesTest {
    private val db = SyncedLibrary.db
    private val sid = SyncedLibrary.serverId
    private val library = SyncedLibrary.library

    // ---- Home ----

    @Test
    fun `recently added and most played albums are the top of their column`() {
        val added = Timings.measure("home: recently added albums (30)") { db.albumDao().observeRecentlyAdded(sid, 30).first() }
        assertEquals(library.albums.sortedByDescending { it.createdAt }.take(30).map { it.id }, added.map { it.id })
        val played = Timings.measure("home: most played albums (30)") { db.albumDao().observeMostPlayed(sid, 30).first() }
        assertEquals(30, played.size)
        assertEquals(library.albums.map { it.playCount }.sortedDescending().take(30), played.map { it.playCount })
    }

    @Test
    fun `recently played albums merge the history with the server's dates`() {
        val albums = Timings.measure("home: recently played albums (30)") { db.albumDao().observeRecentlyPlayed(sid, 30).first() }
        val fromHistory = SyncedLibrary.history.groupBy { it.albumId }.mapValues { (_, plays) -> plays.maxOf { it.playedAt } }
        val expected = library.albums
            .map { it.id to maxOf(it.lastPlayedAt ?: 0, fromHistory[it.id] ?: 0) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(30)
        assertEquals(expected.map { it.first }, albums.map { it.id })
    }

    @Test
    fun `recently played songs come in the order they were last played`() {
        val tracks = Timings.measure("home: recently played tracks (30)") { db.trackDao().observeRecentlyPlayed(sid, 30).first() }
        assertEquals(SyncedLibrary.playedMostRecentFirst.take(30), tracks.map { it.id })
    }

    @Test
    fun `random albums are stable for a seed and change with it`() {
        val a = Timings.measure("home/explore: random albums (100)") { db.albumDao().observeRandom(sid, 100, 42L).first() }
        val b = runBlocking { db.albumDao().observeRandom(sid, 100, 42L).first() }
        // The app draws seeds with Random.nextLong(). The order is a fixed cycle that the seed
        // rotates, so neighbouring seeds (42, 43) give nearly the same picks; distant ones don't.
        val c = runBlocking { db.albumDao().observeRandom(sid, 100, 1_234_567_891L).first() }
        assertEquals(100, a.size)
        assertEquals(a.map { it.id }, b.map { it.id })
        assertNotEquals(a.map { it.id }, c.map { it.id })
    }

    @Test
    fun `shuffle all samples random songs`() {
        val picks = Timings.measure("shuffle: random cached tracks (500)") { db.trackDao().random(sid, 500) }
        assertEquals(500, picks.size)
        assertEquals(500, picks.map { it.id }.toSet().size)
    }

    @Test
    fun `continue listening lists the latest contexts`() {
        val recent = Timings.measure("home: continue listening (20)") { db.playContextDao().observeRecent(sid, 20).first() }
        assertEquals(library.albums.take(20).map { it.id }, recent.map { it.contextId })
    }

    // ---- download rules (DownloadRuleEvaluator.tracksFor) ----

    @Test
    fun `favourites rule takes every starred song`() {
        val liked = Timings.measure("rules: favourites (starred tracks)") { db.trackDao().observeStarred(sid).first() }
        assertEquals(library.tracks.count { it.starred }, liked.size)
        assertSorted("liked by title", liked.map { it.titleSortKey }, NOCASE)
    }

    @Test
    fun `playlist rule keeps the playlist order`() {
        val (playlist, members) = library.playlists.maxBy { it.second.size }
        val tracks = Timings.measure("rules: playlist (${members.size} songs)") { db.playlistDao().observeTracks(sid, playlist.id).first() }
        assertEquals(members.map { it.id }, tracks.map { it.id })
    }

    @Test
    fun `album, artist and genre rules`() {
        val album = library.albums.maxBy { it.trackCount }
        val albumTracks = Timings.measure("rules: album (${album.trackCount} songs)") { db.trackDao().observeForAlbum(sid, album.id).first() }
        assertEquals(album.trackCount, albumTracks.size)
        assertSorted("album order", albumTracks.map { (it.discNumber ?: 1) * 1_000 + (it.trackNumber ?: 0) }, naturalOrder())

        val artist = library.artists.maxBy { it.albumCount }
        val artistTracks = Timings.measure("rules: artist (${artist.albumCount} albums)") { db.trackDao().observeForArtist(sid, artist.id).first() }
        assertEquals(library.tracks.count { it.artistId == artist.id }, artistTracks.size)

        val genre = Timings.measure("rules: genre \"rock\" (any case)") { db.trackDao().observeByGenre(sid, "rock").first() }
        assertEquals(library.tracks.count { it.genre.equals("rock", ignoreCase = true) }, genre.size)
        val genreAlbums = Timings.measure("browse: genre page albums \"rock\"") { db.albumDao().observeByGenre(sid, "rock").first() }
        assertEquals(library.albums.count { it.genre.equals("rock", ignoreCase = true) }, genreAlbums.size)
    }

    @Test
    fun `recently added rule reads the newest albums' songs`() {
        val tracks = Timings.measure("rules: recently added (20 albums, one query each)") {
            db.albumDao().observeRecentlyAdded(sid, 20).first().flatMap { db.trackDao().observeForAlbum(sid, it.id).first() }
        }
        val newest = library.albums.sortedByDescending { it.createdAt }.take(20)
        assertEquals(newest.sumOf { it.trackCount }, tracks.size)
    }

    @Test
    fun `most played rule takes the most played songs, a page not the whole list`() {
        // ascending = true is the sort's natural direction: most played first.
        val top = Timings.measure("rules: most played (100), LIMIT in SQL") {
            db.trackDao().listSorted(SortQueries.tracksSlice(sid, TrackSort.MOST_PLAYED, ascending = true, limit = 100, offset = 0))
        }
        assertEquals(library.tracks.map { it.playCount }.sortedDescending().take(100), top.map { it.playCount })
        val whole = Timings.measure("rules: most played (100), whole Songs list then take(100)", runs = 3) {
            db.trackDao().observeSorted(SortQueries.tracks(sid, TrackSort.MOST_PLAYED, ascending = true)).first().take(100)
        }
        assertEquals(top.map { it.playCount }, whole.map { it.playCount })
    }

    @Test
    fun `recently played rule follows the history`() {
        val tracks = Timings.measure("rules: recently played (200)") { db.trackDao().observeRecentlyPlayed(sid, 200).first() }
        assertEquals(SyncedLibrary.playedMostRecentFirst.take(200), tracks.map { it.id })
    }

    @Test
    fun `the offline index reads 5,000 downloads in chunks`() {
        val downloaded = library.tracks.filterIndexed { i, _ -> i % 12 == 0 }.map { it.id }
        val refs = Timings.measure("offline index: refs and playlists for 5,000 downloads") {
            downloaded.chunked(OfflineFilter.IN_CHUNK).flatMap { chunk ->
                db.playlistDao().getPlaylistIdsForTracks(sid, chunk)
                db.trackDao().getRefs(sid, chunk)
            }
        }
        assertEquals(downloaded.size, refs.size)
    }
}
