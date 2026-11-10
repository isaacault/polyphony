package app.polyphony.core.data.large

import androidx.sqlite.db.SimpleSQLiteQuery
import app.polyphony.core.data.db.SearchQueries
import app.polyphony.core.data.db.SortQueries
import app.polyphony.core.domain.model.AlbumSort
import app.polyphony.core.domain.model.ArtistSort
import app.polyphony.core.domain.model.LibraryFilter
import app.polyphony.core.domain.model.TrackSort
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * `EXPLAIN QUERY PLAN` for the hot queries on the 60,000-track fixture. Every plan is printed (in
 * the test report); the assertions pin the ones that must read an index rather than walk the
 * whole table, so a dropped or unusable index fails here instead of as jank on a phone.
 *
 * The DAO `@Query` strings are repeated here; keep them in step with the DAOs.
 */
class QueryPlansTest {
    private val db = SyncedLibrary.db
    private val sid = SyncedLibrary.serverId

    private fun plan(label: String, query: SimpleSQLiteQuery): List<String> =
        db.queryPlan(query).also { println("PLAN %-44s %s".format(label, it.joinToString(" | "))) }

    private fun plan(label: String, sql: String, vararg args: Any?) = plan(label, SimpleSQLiteQuery(sql, args))

    private fun assertNoTableScan(label: String, plan: List<String>, table: String = "tracks") =
        assertFalse("$label walks all of $table: $plan", plan.scansTable(table))

    @Test
    fun `songs tab sorts read an index`() {
        for (sort in TrackSort.entries) {
            val p = plan("songs $sort", SortQueries.tracks(sid, sort, ascending = true))
            plan("songs $sort reversed", SortQueries.tracks(sid, sort, ascending = false))
            if (sort in INDEXED_TRACK_SORTS) assertNoTableScan("songs $sort", p)
        }
        assertNoTableScan("A-Z keys", plan("songs A-Z keys TITLE", SortQueries.trackSortKeys(sid, TrackSort.TITLE, true)))
        plan("songs count", SortQueries.trackCount(sid, LibraryFilter.NONE))
        plan("songs count, genre", SortQueries.trackCount(sid, LibraryFilter(genre = "Rock")))
        plan("songs TITLE, genre", SortQueries.tracks(sid, TrackSort.TITLE, true, LibraryFilter(genre = "Rock")))
        plan("songs TITLE, favourites", SortQueries.tracks(sid, TrackSort.TITLE, true, LibraryFilter(favouritesOnly = true)))
        plan("shuffle (ORDER BY RANDOM)", "SELECT * FROM tracks WHERE serverId = ? ORDER BY RANDOM() LIMIT ?", sid, 500)
    }

    @Test
    fun `albums and artists tabs`() {
        for (sort in AlbumSort.entries) plan("albums $sort", SortQueries.albums(sid, sort, true))
        for (sort in ArtistSort.entries) plan("artists $sort", SortQueries.artists(sid, sort))
        val newest = db.queryPlan(SortQueries.artists(sid, ArtistSort.RECENTLY_ADDED))
        assertFalse("artists by newest album run a subquery per artist: $newest", newest.any { "CORRELATED" in it })
    }

    @Test
    fun `detail pages and download rules read an index`() {
        val album = "SELECT * FROM tracks WHERE serverId = ? AND albumId = ? " +
            "ORDER BY COALESCE(discNumber, 1), COALESCE(trackNumber, 0), title COLLATE NOCASE"
        assertNoTableScan("album tracks", plan("album tracks", album, sid, "al1"))
        val artist = "SELECT * FROM tracks WHERE serverId = ? AND artistId = ? " +
            "ORDER BY year DESC, albumSortKey, COALESCE(discNumber, 1), COALESCE(trackNumber, 0)"
        assertNoTableScan("artist tracks", plan("artist tracks", artist, sid, "ar1"))
        assertNoTableScan("artist albums", plan("artist albums", "SELECT * FROM albums WHERE serverId = ? AND artistId = ? ORDER BY year DESC, nameSortKey", sid, "ar1"), "albums")
        assertNoTableScan("liked tracks", plan("liked tracks", "SELECT * FROM tracks WHERE serverId = ? AND starred = 1 ORDER BY titleSortKey", sid))
        val playlist = "SELECT tracks.* FROM playlist_tracks JOIN tracks ON tracks.serverId = playlist_tracks.serverId AND tracks.id = playlist_tracks.trackId " +
            "WHERE playlist_tracks.serverId = ? AND playlist_tracks.playlistId = ? ORDER BY playlist_tracks.position"
        assertNoTableScan("playlist tracks", plan("playlist tracks", playlist, sid, "pl1"))
        plan(
            "genre tracks",
            "SELECT * FROM tracks WHERE serverId = ? AND genre = ? COLLATE NOCASE " +
                "ORDER BY artistSortKey, albumSortKey, COALESCE(discNumber, 1), COALESCE(trackNumber, 0)",
            sid, "rock",
        )
        plan("genre albums", "SELECT * FROM albums WHERE serverId = ? AND genre = ? COLLATE NOCASE ORDER BY artistSortKey, year, nameSortKey", sid, "rock")
        plan(
            "appears on (LIKE)",
            "SELECT * FROM tracks WHERE serverId = ? AND albumId IS NOT NULL AND artistName LIKE '%' || ? || '%' " +
                "AND (albumArtistName IS NULL OR albumArtistName != ? COLLATE NOCASE) LIMIT 2000",
            sid, "Anna Walker", "Anna Walker",
        )
        plan("offline refs", "SELECT id, albumId, artistId, genre FROM tracks WHERE serverId = ? AND id IN (?, ?, ?)", sid, "t1", "t2", "t3")
    }

    @Test
    fun `home shelves`() {
        assertNoTableScan(
            "recently added albums",
            plan("recently added albums", "SELECT * FROM albums WHERE serverId = ? ORDER BY createdAt DESC, name COLLATE NOCASE LIMIT ?", sid, 30),
            "albums",
        )
        plan("most played albums", "SELECT * FROM albums WHERE serverId = ? AND playCount > 0 ORDER BY playCount DESC, name COLLATE NOCASE LIMIT ?", sid, 30)
        plan(
            "recently played albums",
            "SELECT a.* FROM albums a LEFT JOIN " +
                "(SELECT albumId, MAX(playedAt) AS lastPlayed FROM play_history WHERE serverId = ? GROUP BY albumId) h " +
                "ON h.albumId = a.id WHERE a.serverId = ? AND (a.lastPlayedAt IS NOT NULL OR h.lastPlayed IS NOT NULL) " +
                "ORDER BY MAX(COALESCE(a.lastPlayedAt, 0), COALESCE(h.lastPlayed, 0)) DESC LIMIT ?",
            sid, sid, 30,
        )
        plan(
            "recently played tracks",
            "SELECT t.* FROM tracks t JOIN " +
                "(SELECT trackId, MAX(playedAt) AS lastPlayed FROM play_history WHERE serverId = ? GROUP BY trackId) h " +
                "ON h.trackId = t.id WHERE t.serverId = ? ORDER BY h.lastPlayed DESC LIMIT ?",
            sid, sid, 30,
        )
        plan("random albums", "SELECT * FROM albums WHERE serverId = ? ORDER BY ABS((rowid * 1103515245 + ?) % 2147483647) LIMIT ?", sid, 42, 100)
        plan(
            "history prune",
            "DELETE FROM play_history WHERE serverId = ? AND id NOT IN (SELECT id FROM play_history WHERE serverId = ? ORDER BY playedAt DESC LIMIT ?)",
            sid, sid, 5_000,
        )
    }

    @Test
    fun `search uses the full-text index`() {
        val fts = plan("search tracks FTS", SearchQueries.tracksFts(sid, "heart*", LibraryFilter.NONE, 50))
        assertNoTableScan("search tracks FTS", fts)
        plan("search tracks LIKE", SearchQueries.tracksLike(sid, "%eart%", LibraryFilter.NONE, 50))
        plan("search albums FTS", SearchQueries.albumsFts(sid, "heart*", LibraryFilter.NONE, 50))
        plan("search artists FTS", SearchQueries.artistsFts(sid, "heart*", LibraryFilter.NONE, 50))
    }

    @Test
    fun `sync cleanup`() {
        plan("delete stale tracks", "DELETE FROM tracks WHERE serverId = ? AND syncGeneration < ?", sid, 2L)
        plan("album track counts", "SELECT id, trackCount FROM albums WHERE serverId = ?", sid)
    }

    private companion object {
        val INDEXED_TRACK_SORTS = setOf(TrackSort.TITLE, TrackSort.ARTIST, TrackSort.ALBUM, TrackSort.RECENTLY_ADDED, TrackSort.MOST_PLAYED)
    }
}
