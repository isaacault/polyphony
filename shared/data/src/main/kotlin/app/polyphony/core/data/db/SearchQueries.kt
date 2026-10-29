package app.polyphony.core.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import app.polyphony.core.domain.model.ItemKind
import app.polyphony.core.domain.model.LibraryFilter

/**
 * The local search queries (prefix FTS, and the LIKE fallback for mid-word fragments) with a
 * [LibraryFilter] applied in SQL, so a narrow filter still fills the result limit instead of
 * filtering a handful of rows after it. Same shape as the DAO `searchFts` / `searchLike` queries.
 */
internal object SearchQueries {

    fun tracksFts(serverId: String, match: String, filter: LibraryFilter, limit: Int): SimpleSQLiteQuery {
        val where = SortQueries.filterClause(filter.restrictedTo(ItemKind.TRACK)) { "tracks.$it" }
        return SimpleSQLiteQuery(
            "SELECT tracks.* FROM tracks JOIN tracks_fts ON tracks.rowid = tracks_fts.rowid " +
                "WHERE tracks_fts MATCH ? AND tracks.serverId = ?${where.sql} ORDER BY tracks.title COLLATE NOCASE LIMIT ?",
            where.args(match, serverId, trailing = listOf(limit)),
        )
    }

    fun tracksLike(serverId: String, pattern: String, filter: LibraryFilter, limit: Int): SimpleSQLiteQuery {
        val where = SortQueries.filterClause(filter.restrictedTo(ItemKind.TRACK))
        return SimpleSQLiteQuery(
            "SELECT * FROM tracks WHERE serverId = ? AND (title LIKE ? ESCAPE '\\' OR artistName LIKE ? ESCAPE '\\' " +
                "OR albumName LIKE ? ESCAPE '\\')${where.sql} ORDER BY title COLLATE NOCASE LIMIT ?",
            where.args(serverId, pattern, pattern, pattern, trailing = listOf(limit)),
        )
    }

    fun albumsFts(serverId: String, match: String, filter: LibraryFilter, limit: Int): SimpleSQLiteQuery {
        val where = SortQueries.filterClause(filter.restrictedTo(ItemKind.ALBUM)) { "albums.$it" }
        return SimpleSQLiteQuery(
            "SELECT albums.* FROM albums JOIN albums_fts ON albums.rowid = albums_fts.rowid " +
                "WHERE albums_fts MATCH ? AND albums.serverId = ?${where.sql} ORDER BY albums.name COLLATE NOCASE LIMIT ?",
            where.args(match, serverId, trailing = listOf(limit)),
        )
    }

    fun albumsLike(serverId: String, pattern: String, filter: LibraryFilter, limit: Int): SimpleSQLiteQuery {
        val where = SortQueries.filterClause(filter.restrictedTo(ItemKind.ALBUM))
        return SimpleSQLiteQuery(
            "SELECT * FROM albums WHERE serverId = ? AND (name LIKE ? ESCAPE '\\' OR artistName LIKE ? ESCAPE '\\')" +
                "${where.sql} ORDER BY name COLLATE NOCASE LIMIT ?",
            where.args(serverId, pattern, pattern, trailing = listOf(limit)),
        )
    }

    fun artistsFts(serverId: String, match: String, filter: LibraryFilter, limit: Int): SimpleSQLiteQuery {
        val where = SortQueries.filterClause(filter.restrictedTo(ItemKind.ARTIST)) { "artists.$it" }
        return SimpleSQLiteQuery(
            "SELECT artists.* FROM artists JOIN artists_fts ON artists.rowid = artists_fts.rowid " +
                "WHERE artists_fts MATCH ? AND artists.serverId = ?${where.sql} ORDER BY artists.name COLLATE NOCASE LIMIT ?",
            where.args(match, serverId, trailing = listOf(limit)),
        )
    }

    fun artistsLike(serverId: String, pattern: String, filter: LibraryFilter, limit: Int): SimpleSQLiteQuery {
        val where = SortQueries.filterClause(filter.restrictedTo(ItemKind.ARTIST))
        return SimpleSQLiteQuery(
            "SELECT * FROM artists WHERE serverId = ? AND name LIKE ? ESCAPE '\\'${where.sql} ORDER BY name COLLATE NOCASE LIMIT ?",
            where.args(serverId, pattern, trailing = listOf(limit)),
        )
    }
}
