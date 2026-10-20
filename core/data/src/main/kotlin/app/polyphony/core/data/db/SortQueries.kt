package app.polyphony.core.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import app.polyphony.core.domain.model.AlbumSort
import app.polyphony.core.domain.model.ArtistSort
import app.polyphony.core.domain.model.TrackSort

/**
 * Builds sorted list queries from a fixed whitelist of ORDER BY clauses. User input is only ever
 * bound as an argument; nothing from outside this object is concatenated into SQL.
 *
 * `ascending = true` means the sort's natural direction (A→Z for names, newest first for dates,
 * most first for counts); `false` reverses it.
 */
internal object SortQueries {

    fun artists(serverId: String, sort: ArtistSort): SimpleSQLiteQuery {
        val order = when (sort) {
            ArtistSort.NAME -> "COALESCE(sortName, name) COLLATE NOCASE ASC"
            ArtistSort.ALBUM_COUNT -> "albumCount DESC, COALESCE(sortName, name) COLLATE NOCASE ASC"
            ArtistSort.RECENTLY_ADDED ->
                "(SELECT MAX(a.createdAt) FROM albums a WHERE a.serverId = artists.serverId AND a.artistId = artists.id) DESC, " +
                    "COALESCE(sortName, name) COLLATE NOCASE ASC"
            ArtistSort.STARRED -> "starred DESC, COALESCE(sortName, name) COLLATE NOCASE ASC"
        }
        return SimpleSQLiteQuery("SELECT * FROM artists WHERE serverId = ? ORDER BY $order", arrayOf(serverId))
    }

    fun albums(serverId: String, sort: AlbumSort, ascending: Boolean): SimpleSQLiteQuery {
        val name = "COALESCE(sortName, name) COLLATE NOCASE"
        val (primary, naturalAsc) = when (sort) {
            AlbumSort.NAME -> name to true
            AlbumSort.ARTIST -> "artistName COLLATE NOCASE" to true
            AlbumSort.YEAR -> "COALESCE(year, 0)" to false
            AlbumSort.RECENTLY_ADDED -> "createdAt" to false
            AlbumSort.RECENTLY_PLAYED -> "COALESCE(lastPlayedAt, 0)" to false
            AlbumSort.MOST_PLAYED -> "playCount" to false
            AlbumSort.RATING -> "rating" to false
            AlbumSort.DURATION -> "durationMs" to false
        }
        val dir = direction(naturalAsc, ascending)
        val tieBreak = when (sort) {
            AlbumSort.ARTIST -> ", COALESCE(year, 0) ASC, $name ASC"
            AlbumSort.NAME -> ", artistName COLLATE NOCASE ASC"
            else -> ", $name ASC"
        }
        return SimpleSQLiteQuery(
            "SELECT * FROM albums WHERE serverId = ? ORDER BY $primary $dir$tieBreak",
            arrayOf(serverId),
        )
    }

    fun tracks(serverId: String, sort: TrackSort, ascending: Boolean): SimpleSQLiteQuery =
        SimpleSQLiteQuery("SELECT * FROM tracks WHERE serverId = ? ORDER BY ${trackOrder(sort, ascending)}", arrayOf(serverId))

    /** One window of [tracks], in the same order. */
    fun tracksSlice(serverId: String, sort: TrackSort, ascending: Boolean, limit: Int, offset: Int): SimpleSQLiteQuery =
        SimpleSQLiteQuery(
            "SELECT * FROM tracks WHERE serverId = ? ORDER BY ${trackOrder(sort, ascending)} LIMIT ? OFFSET ?",
            arrayOf<Any>(serverId, limit, offset),
        )

    /**
     * Only the first character of the column [tracks] sorts by, in list order: enough to build an
     * alphabet-rail index without reading a row per track.
     */
    fun trackSortKeys(serverId: String, sort: TrackSort, ascending: Boolean): SimpleSQLiteQuery {
        val column = when (sort) {
            TrackSort.ARTIST -> "artistName"
            TrackSort.ALBUM -> "albumName"
            else -> "title"
        }
        return SimpleSQLiteQuery(
            "SELECT substr(trim($column), 1, 1) AS k FROM tracks WHERE serverId = ? ORDER BY ${trackOrder(sort, ascending)}",
            arrayOf(serverId),
        )
    }

    private fun trackOrder(sort: TrackSort, ascending: Boolean): String {
        val (primary, naturalAsc) = when (sort) {
            TrackSort.TITLE -> "title COLLATE NOCASE" to true
            TrackSort.ARTIST -> "artistName COLLATE NOCASE" to true
            TrackSort.ALBUM -> "albumName COLLATE NOCASE" to true
            TrackSort.DURATION -> "durationMs" to false
            TrackSort.RECENTLY_ADDED -> "createdAt" to false
            TrackSort.MOST_PLAYED -> "playCount" to false
            TrackSort.RATING -> "rating" to false
        }
        val dir = direction(naturalAsc, ascending)
        val tieBreak = when (sort) {
            TrackSort.ALBUM, TrackSort.ARTIST ->
                ", albumName COLLATE NOCASE ASC, COALESCE(discNumber, 1) ASC, COALESCE(trackNumber, 0) ASC"
            else -> ", title COLLATE NOCASE ASC"
        }
        return "$primary $dir$tieBreak"
    }

    private fun direction(naturalAsc: Boolean, ascending: Boolean): String =
        if (naturalAsc == ascending) "ASC" else "DESC"
}
