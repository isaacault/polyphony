package app.polyphony.core.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import app.polyphony.core.domain.model.SmartField
import app.polyphony.core.domain.model.SmartOp
import app.polyphony.core.domain.model.SmartPlaylist
import app.polyphony.core.domain.model.SmartRule

/**
 * A [SmartPlaylist] (#33) as a query over `tracks`. As in [SortQueries], the SQL comes only from
 * this file's whitelist of columns and operators; every value is bound as an argument.
 */
internal object SmartPlaylistQueries {

    /** The playlist's songs on [serverId], in its order, as of [nowMs] for the day rules. */
    fun tracks(serverId: String, playlist: SmartPlaylist, nowMs: Long): SimpleSQLiteQuery {
        val where = where(playlist, nowMs)
        val limit = if (playlist.limit > 0) " LIMIT ${playlist.limit}" else ""
        return SimpleSQLiteQuery(
            "SELECT * FROM tracks WHERE serverId = ?${where.sql} ORDER BY ${SortQueries.trackOrder(playlist.sort, playlist.ascending)}$limit",
            where.args(serverId),
        )
    }

    /** How many songs [tracks] finds, without reading them (the editor counts as the rules change). */
    fun count(serverId: String, playlist: SmartPlaylist, nowMs: Long): SimpleSQLiteQuery {
        val where = where(playlist, nowMs)
        val limit = if (playlist.limit > 0) " LIMIT ${playlist.limit}" else ""
        return SimpleSQLiteQuery("SELECT COUNT(*) FROM (SELECT 1 FROM tracks WHERE serverId = ?${where.sql}$limit)", where.args(serverId))
    }

    /** ` AND (…)` for the playlist's rules, joined by AND or OR; nothing when no rule says anything. */
    fun where(playlist: SmartPlaylist, nowMs: Long): FilterClause {
        val parts = ArrayList<String>()
        val args = ArrayList<Any>()
        for (rule in playlist.activeRules) {
            val (sql, bound) = condition(rule, nowMs)
            parts.add(sql)
            args.addAll(bound)
        }
        if (parts.isEmpty()) return FilterClause("", emptyList())
        val joined = parts.joinToString(if (playlist.matchAll) " AND " else " OR ") { "($it)" }
        return FilterClause(" AND ($joined)", args)
    }

    private fun condition(rule: SmartRule, nowMs: Long): Pair<String, List<Any>> = when (rule.field.kind) {
        SmartField.Kind.TEXT -> text(textColumns(rule.field), rule.op, rule.value.trim())
        SmartField.Kind.NUMBER -> number(rule.field, rule.op, rule.number)
        SmartField.Kind.DAYS -> {
            val column = if (rule.field == SmartField.ADDED) "createdAt" else "lastPlayedAt"
            val cutoff = nowMs - rule.number * DAY_MS
            if (rule.op == SmartOp.WITHIN_DAYS) "$column >= ?" to listOf(cutoff)
            else "$column IS NULL OR $column < ?" to listOf(cutoff)
        }
        SmartField.Kind.FLAG -> (if (rule.op == SmartOp.IS_TRUE) "starred = 1" else "starred = 0") to emptyList()
    }

    /** The columns a text rule reads: an artist is the song's or its album's. */
    private fun textColumns(field: SmartField): List<String> = when (field) {
        SmartField.TITLE -> listOf("title")
        SmartField.ARTIST -> listOf("artistName", "albumArtistName")
        SmartField.ALBUM -> listOf("albumName")
        SmartField.GENRE -> listOf("genre")
        SmartField.FORMAT -> listOf("container")
        else -> error("$field is not a text field")
    }

    /**
     * Case-insensitive, as the library's names are shown. A negation also takes songs with no
     * value at all ("genre isn't Rock" includes songs with no genre).
     */
    private fun text(columns: List<String>, op: SmartOp, value: String): Pair<String, List<Any>> {
        val pattern = "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        fun any(test: (String) -> String) = columns.joinToString(" OR ") { test(it) }
        return when (op) {
            SmartOp.IS -> any { "$it = ? COLLATE NOCASE" } to columns.map { value }
            SmartOp.CONTAINS -> any { "$it LIKE ? ESCAPE '\\'" } to columns.map { pattern }
            SmartOp.IS_NOT -> "NOT (${any { "COALESCE($it, '') = ? COLLATE NOCASE" }})" to columns.map { value }
            SmartOp.NOT_CONTAINS -> "NOT (${any { "COALESCE($it, '') LIKE ? ESCAPE '\\'" }})" to columns.map { pattern }
            else -> error("$op is not a text operator")
        }
    }

    private fun number(field: SmartField, op: SmartOp, n: Int): Pair<String, List<Any>> {
        // Minutes are compared as the milliseconds stored; "is 4" is anything from 4:00 to 4:59.
        if (field == SmartField.DURATION_MINUTES) {
            val from = n * MINUTE_MS
            return when (op) {
                SmartOp.AT_LEAST -> "durationMs >= ?" to listOf(from)
                SmartOp.AT_MOST -> "durationMs < ?" to listOf(from + MINUTE_MS)
                else -> "durationMs >= ? AND durationMs < ?" to listOf(from, from + MINUTE_MS)
            }
        }
        val column = when (field) {
            SmartField.YEAR -> "year"
            SmartField.RATING -> "rating"
            SmartField.PLAY_COUNT -> "playCount"
            SmartField.BITRATE_KBPS -> "bitrateKbps"
            else -> error("$field is not a number field")
        }
        return when (op) {
            SmartOp.AT_LEAST -> "$column >= ?"
            SmartOp.AT_MOST -> "$column <= ?"
            else -> "$column = ?"
        } to listOf(n.toLong())
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val MINUTE_MS = 60_000L
}
