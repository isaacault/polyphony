package app.polyphony.core.data.large

import androidx.sqlite.db.SimpleSQLiteQuery
import app.polyphony.core.data.db.SmartPlaylistQueries
import app.polyphony.core.data.db.SortQueries
import app.polyphony.core.domain.model.SmartField
import app.polyphony.core.domain.model.SmartOp
import app.polyphony.core.domain.model.SmartPlaylist
import app.polyphony.core.domain.model.SmartRule
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.model.TrackSort
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smart playlists (#33) on the 60,000-track library: each query's songs against the same rules
 * applied in Kotlin to the library it was synced from.
 */
class SmartPlaylistQueriesTest {
    private val db = SyncedLibrary.db
    private val sid = SyncedLibrary.serverId
    private val tracks = SyncedLibrary.library.tracks
    private val now = tracks.maxOf { it.createdAt } + 1

    private fun ids(playlist: SmartPlaylist): List<String> = ids(SmartPlaylistQueries.tracks(sid, playlist, now))

    private fun ids(query: SimpleSQLiteQuery): List<String> =
        runBlocking { db.trackDao().observeSorted(query).first() }.map { it.id }

    private fun playlist(vararg rules: SmartRule, matchAll: Boolean = true) =
        SmartPlaylist(id = "p", name = "p", rules = rules.toList(), matchAll = matchAll)

    private fun expect(filter: (Track) -> Boolean) = tracks.filter(filter).map { it.id }.toSet()

    @Test fun `genre is matches every spelling of it`() {
        val got = ids(playlist(SmartRule(SmartField.GENRE, SmartOp.IS, "jazz"))).toSet()
        assertEquals(expect { it.genre.equals("Jazz", ignoreCase = true) }, got)
        assertTrue(got.isNotEmpty())
    }

    @Test fun `a negation includes songs with no value`() {
        val got = ids(playlist(SmartRule(SmartField.GENRE, SmartOp.IS_NOT, "Rock"))).toSet()
        assertEquals(expect { !it.genre.equals("Rock", ignoreCase = true) }, got)
    }

    @Test fun `any of, favourites or rated four and up`() {
        val got = ids(
            playlist(
                SmartRule(SmartField.FAVOURITE, SmartOp.IS_TRUE),
                SmartRule(SmartField.RATING, SmartOp.AT_LEAST, "4"),
                matchAll = false,
            ),
        ).toSet()
        assertEquals(expect { it.starred || it.rating >= 4 }, got)
    }

    @Test fun `all of format, length and bitrate`() {
        val got = ids(
            playlist(
                SmartRule(SmartField.FORMAT, SmartOp.CONTAINS, "FLA"),
                SmartRule(SmartField.DURATION_MINUTES, SmartOp.AT_LEAST, "5"),
                SmartRule(SmartField.BITRATE_KBPS, SmartOp.AT_LEAST, "1000"),
            ),
        ).toSet()
        assertEquals(expect { it.container == "flac" && it.durationMs >= 300_000 && (it.bitrateKbps ?: 0) >= 1000 }, got)
    }

    @Test fun `an artist is the song's or its album's`() {
        val name = tracks.first { it.albumArtistName != null && it.albumArtistName != it.artistName }.albumArtistName!!
        val got = ids(playlist(SmartRule(SmartField.ARTIST, SmartOp.IS, name))).toSet()
        assertEquals(expect { it.artistName.equals(name, true) || it.albumArtistName.equals(name, true) }, got)
    }

    @Test fun `added within the last days`() {
        val got = ids(playlist(SmartRule(SmartField.ADDED, SmartOp.WITHIN_DAYS, "60"))).toSet()
        assertEquals(expect { it.createdAt >= now - 60L * 86_400_000 }, got)
    }

    @Test fun `not played lately includes never played`() {
        val cutoff = now - 30L * 86_400_000
        val got = ids(playlist(SmartRule(SmartField.LAST_PLAYED, SmartOp.NOT_WITHIN_DAYS, "30"))).size.toLong()
        assertEquals(db.longValue("SELECT COUNT(*) FROM tracks WHERE serverId = ? AND (lastPlayedAt IS NULL OR lastPlayedAt < ?)", sid, cutoff), got)
    }

    @Test fun `sort and limit are the Songs tab's`() {
        val p = playlist(SmartRule(SmartField.RATING, SmartOp.AT_LEAST, "1")).copy(sort = TrackSort.RATING, limit = 25)
        val all = ids(SortQueries.tracks(sid, TrackSort.RATING, ascending = true)).filter { id -> tracks.first { it.id == id }.rating >= 1 }
        assertEquals(all.take(25), ids(p))
    }

    @Test fun `unfinished rules narrow nothing, and wildcards are taken literally`() {
        val unfinished = playlist(SmartRule(SmartField.YEAR, SmartOp.AT_LEAST, "abc"), SmartRule(SmartField.TITLE, SmartOp.CONTAINS, " "))
        assertEquals(tracks.size, ids(unfinished).size)
        val where = SmartPlaylistQueries.where(playlist(SmartRule(SmartField.TITLE, SmartOp.CONTAINS, "50%_off")), now)
        assertEquals(listOf<Any>("%50\\%\\_off%"), where.bound)
        assertEquals(0, ids(playlist(SmartRule(SmartField.TITLE, SmartOp.CONTAINS, "%"))).size)
    }
}
