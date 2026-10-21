package app.polyphony.core.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import app.polyphony.core.domain.model.AlbumSort
import app.polyphony.core.domain.model.ArtistSort
import app.polyphony.core.domain.model.ItemKind
import app.polyphony.core.domain.model.LEADING_ARTICLES
import app.polyphony.core.domain.model.LibraryFilter
import app.polyphony.core.domain.model.TrackSort

/**
 * Builds sorted list queries from a fixed whitelist of ORDER BY clauses. User input is only ever
 * bound as an argument; nothing from outside this object is concatenated into SQL.
 *
 * `ascending = true` means the sort's natural direction (A→Z for names, newest first for dates,
 * most first for counts); `false` reverses it.
 *
 * A–Z orders read the stored `*SortKey` columns (NOCASE, leading articles dropped; see `sortKey`),
 * so "The Strokes" sorts as "Strokes" and the Songs list can walk an index instead of sorting.
 */
internal object SortQueries {

    /**
     * SQL twin of `sortKey` (core:domain): [column] trimmed and without a leading "The ", "An " or
     * "A ". Only the migration that backfills the sort-key columns uses it; new rows get their keys
     * from `sortKey` in Kotlin. `LIKE` is case-insensitive for ASCII, like `startsWith(ignoreCase)`.
     */
    fun sortable(column: String): String {
        val c = "trim($column)"
        val cases = LEADING_ARTICLES.joinToString(" ") { article ->
            "WHEN $c LIKE '$article%' AND length($c) > ${article.length} THEN substr($c, ${article.length + 1})"
        }
        return "(CASE $cases ELSE $c END)"
    }

    /**
     * `AND …` conditions for [filter] on a table with `starred`/`rating`/`genre`/`year` columns,
     * with their arguments. Callers pass a filter already [LibraryFilter.restrictedTo] their kind.
     * Downloaded is not here: it needs the downloads index and is applied after the query.
     * [column] qualifies a column name when the query joins another table.
     */
    fun filterClause(filter: LibraryFilter, column: (String) -> String = { it }): FilterClause {
        val parts = ArrayList<String>()
        val args = ArrayList<Any>()
        if (filter.favouritesOnly) parts += "${column("starred")} = 1"
        if (filter.minRating > 0) {
            parts += "${column("rating")} >= ?"
            args += filter.minRating
        }
        filter.genre?.let {
            parts += "${column("genre")} = ? COLLATE NOCASE"
            args += it
        }
        if (filter.yearFrom != null || filter.yearTo != null) parts += "${column("year")} > 0"
        filter.yearFrom?.let {
            parts += "${column("year")} >= ?"
            args += it
        }
        filter.yearTo?.let {
            parts += "${column("year")} <= ?"
            args += it
        }
        return FilterClause(parts.joinToString("") { " AND $it" }, args)
    }

    fun artists(serverId: String, sort: ArtistSort, filter: LibraryFilter = LibraryFilter.NONE): SimpleSQLiteQuery {
        val order = when (sort) {
            ArtistSort.NAME -> "nameSortKey ASC"
            ArtistSort.ALBUM_COUNT -> "albumCount DESC, nameSortKey ASC"
            ArtistSort.RECENTLY_ADDED ->
                "(SELECT MAX(a.createdAt) FROM albums a WHERE a.serverId = artists.serverId AND a.artistId = artists.id) DESC, " +
                    "nameSortKey ASC"
            ArtistSort.STARRED -> "starred DESC, nameSortKey ASC"
        }
        val where = filterClause(filter.restrictedTo(ItemKind.ARTIST))
        return SimpleSQLiteQuery("SELECT * FROM artists WHERE serverId = ?${where.sql} ORDER BY $order", where.args(serverId))
    }

    fun albums(serverId: String, sort: AlbumSort, ascending: Boolean, filter: LibraryFilter = LibraryFilter.NONE): SimpleSQLiteQuery {
        val name = "nameSortKey"
        val (primary, naturalAsc) = when (sort) {
            AlbumSort.NAME -> name to true
            AlbumSort.ARTIST -> "artistSortKey" to true
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
            AlbumSort.NAME -> ", artistSortKey ASC"
            else -> ", $name ASC"
        }
        val where = filterClause(filter.restrictedTo(ItemKind.ALBUM))
        return SimpleSQLiteQuery(
            "SELECT * FROM albums WHERE serverId = ?${where.sql} ORDER BY $primary $dir$tieBreak",
            where.args(serverId),
        )
    }

    fun tracks(serverId: String, sort: TrackSort, ascending: Boolean, filter: LibraryFilter = LibraryFilter.NONE): SimpleSQLiteQuery {
        val where = trackWhere(filter)
        return SimpleSQLiteQuery("SELECT * FROM tracks WHERE serverId = ?${where.sql} ORDER BY ${trackOrder(sort, ascending)}", where.args(serverId))
    }

    /** One window of [tracks], in the same order. */
    fun tracksSlice(
        serverId: String,
        sort: TrackSort,
        ascending: Boolean,
        limit: Int,
        offset: Int,
        filter: LibraryFilter = LibraryFilter.NONE,
    ): SimpleSQLiteQuery {
        val where = trackWhere(filter)
        return SimpleSQLiteQuery(
            "SELECT * FROM tracks WHERE serverId = ?${where.sql} ORDER BY ${trackOrder(sort, ascending)} LIMIT ? OFFSET ?",
            where.args(serverId, trailing = listOf(limit, offset)),
        )
    }

    /** How many rows [tracks] returns for [filter]. */
    fun trackCount(serverId: String, filter: LibraryFilter): SimpleSQLiteQuery {
        val where = trackWhere(filter)
        return SimpleSQLiteQuery("SELECT COUNT(*) FROM tracks WHERE serverId = ?${where.sql}", where.args(serverId))
    }

    /** Ids of the rows [tracks] returns for [filter], for counting against the downloads index. */
    fun trackIds(serverId: String, filter: LibraryFilter): SimpleSQLiteQuery {
        val where = trackWhere(filter)
        return SimpleSQLiteQuery("SELECT id FROM tracks WHERE serverId = ?${where.sql}", where.args(serverId))
    }

    /** A random sample of the rows [tracks] returns for [filter]: Shuffle on a filtered Songs list. */
    fun randomTracks(serverId: String, filter: LibraryFilter, limit: Int): SimpleSQLiteQuery {
        val where = trackWhere(filter)
        return SimpleSQLiteQuery("SELECT * FROM tracks WHERE serverId = ?${where.sql} ORDER BY RANDOM() LIMIT ?", where.args(serverId, trailing = listOf(limit)))
    }

    private fun trackWhere(filter: LibraryFilter) = filterClause(filter.restrictedTo(ItemKind.TRACK))

    /**
     * Only the first character of the column [tracks] sorts by, in list order: enough to build an
     * alphabet-rail index without reading a row per track.
     */
    fun trackSortKeys(serverId: String, sort: TrackSort, ascending: Boolean, filter: LibraryFilter = LibraryFilter.NONE): SimpleSQLiteQuery {
        val column = when (sort) {
            TrackSort.ARTIST -> "artistSortKey"
            TrackSort.ALBUM -> "albumSortKey"
            else -> "titleSortKey"
        }
        val where = trackWhere(filter)
        return SimpleSQLiteQuery(
            "SELECT substr($column, 1, 1) AS k FROM tracks WHERE serverId = ?${where.sql} ORDER BY ${trackOrder(sort, ascending)}",
            where.args(serverId),
        )
    }

    private fun trackOrder(sort: TrackSort, ascending: Boolean): String {
        val (primary, naturalAsc) = when (sort) {
            TrackSort.TITLE -> "titleSortKey" to true
            TrackSort.ARTIST -> "artistSortKey" to true
            TrackSort.ALBUM -> "albumSortKey" to true
            TrackSort.DURATION -> "durationMs" to false
            TrackSort.RECENTLY_ADDED -> "createdAt" to false
            TrackSort.MOST_PLAYED -> "playCount" to false
            TrackSort.RATING -> "rating" to false
        }
        val dir = direction(naturalAsc, ascending)
        val tieBreak = when (sort) {
            TrackSort.ALBUM, TrackSort.ARTIST ->
                ", albumSortKey ASC, COALESCE(discNumber, 1) ASC, COALESCE(trackNumber, 0) ASC"
            TrackSort.TITLE -> ", title COLLATE NOCASE ASC"
            else -> ", titleSortKey ASC"
        }
        return "$primary $dir$tieBreak"
    }

    private fun direction(naturalAsc: Boolean, ascending: Boolean): String =
        if (naturalAsc == ascending) "ASC" else "DESC"
}

/** SQL conditions (each prefixed with ` AND `) and the arguments they bind, in order. */
internal data class FilterClause(val sql: String, val bound: List<Any>) {
    /** [leading] (the server id, …), then these conditions' arguments, then [trailing] (LIMIT/OFFSET). */
    fun args(vararg leading: Any, trailing: List<Any> = emptyList()): Array<Any> =
        (leading.toList() + bound + trailing).toTypedArray()
}
