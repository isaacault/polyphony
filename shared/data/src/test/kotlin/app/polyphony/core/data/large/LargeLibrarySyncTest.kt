package app.polyphony.core.data.large

import app.polyphony.core.data.db.PolyphonyDatabase
import app.polyphony.core.data.mappers.toEntity
import app.polyphony.core.data.sync.LibrarySyncEngine
import app.polyphony.core.data.sync.RoomSyncSink
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Track
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sync engine writing a 60,000-track server into Room (issues #9 and #24): a first sync, a
 * second full sync after the server lost and gained albums (generation-based deletes), and an
 * incremental sync. Each test syncs into a fresh in-memory database.
 */
class LargeLibrarySyncTest {
    private val library = SyncedLibrary.library
    private val sid = library.serverId
    private val db: PolyphonyDatabase = TestDatabase.inMemory()
    private val engine = LibrarySyncEngine()

    @After
    fun close() = db.close()

    private fun sync(provider: LargeLibraryProvider, generation: Long, full: Boolean, label: String) =
        Timings.once(label) { engine.run(provider, RoomSyncSink(db), generation, full) { _, _ -> } }

    private fun count(table: String) = db.longValue("SELECT COUNT(*) FROM $table WHERE serverId = ?", sid)

    /** Search-index entries for a prefix, straight from the FTS index (an orphaned entry counts too). */
    private fun ftsHits(prefix: String) = db.longValue("SELECT COUNT(*) FROM tracks_fts WHERE tracks_fts MATCH ?", "$prefix*")

    /** How many [tracks] a prefix search must find: any title, artist or album word starting with it. */
    private fun expectedHits(tracks: List<Track>, prefix: String) = tracks.count { t ->
        listOfNotNull(t.title, t.artistName, t.albumName).any { field ->
            Regex("[\\p{L}\\p{N}]+").findAll(field.lowercase()).any { it.value.startsWith(prefix) }
        }
    }.toLong()

    @Test
    fun `a first full sync writes every row, playlist, favourite and search entry`() {
        val provider = LargeLibraryProvider(library)
        val summary = sync(provider, generation = 1, full = true, label = "sync: first full sync (60k tracks)")

        assertEquals(60_000, summary.tracks)
        assertEquals(library.artists.size.toLong(), count("artists"))
        assertEquals(library.albums.size.toLong(), count("albums"))
        assertEquals(library.tracks.size.toLong(), count("tracks"))
        assertEquals(library.playlists.size.toLong(), count("playlists"))
        assertEquals(library.playlists.sumOf { it.second.size }.toLong(), count("playlist_tracks"))
        assertEquals(library.genres.size.toLong(), count("genres"))
        assertEquals(library.tracks.count { it.starred }.toLong(), db.longValue("SELECT COUNT(*) FROM tracks WHERE serverId = ? AND starred = 1", sid))
        assertEquals(library.albums.count { it.starred }.toLong(), db.longValue("SELECT COUNT(*) FROM albums WHERE serverId = ? AND starred = 1", sid))
        assertEquals(expectedHits(library.tracks, "heart"), ftsHits("heart"))
        // Sort keys are derived on write: "The Hollow Hearts" files under H.
        val the = library.artists.first { it.name.startsWith("The ") }
        assertEquals(the.name.removePrefix("The "), runBlocking { db.artistDao().get(sid, the.id) }!!.nameSortKey)
    }

    @Test
    fun `a second full sync deletes what the server lost and keeps the rest`() {
        val provider = LargeLibraryProvider(library)
        sync(provider, generation = 1, full = true, label = "sync: first full sync (setup)")

        val goneAlbums = library.albums.filter { it.releaseType != null }.take(25).map { it.id }.toSet()
        val goneTracks = library.tracks.filter { it.albumId !in goneAlbums }.filterIndexed { i, _ -> i % 200 == 7 }.map { it.id }.toSet()
        provider.removeAlbums(goneAlbums)
        provider.removeTracks(goneTracks)
        val newAlbum = Album(id = "al-new", serverId = sid, name = "Heartland Sessions", artistId = "ar1", artistName = library.artists[1].name,
            createdAt = LargeLibrary.BASE_TIME + 86_400_000L)
        val newTracks = (1..12).map { Track(id = "t-new-$it", serverId = sid, title = "Heart Song $it", albumId = newAlbum.id, albumName = newAlbum.name) }
        provider.addAlbum(newAlbum, newTracks)

        sync(provider, generation = 2, full = true, label = "sync: second full sync, rows already there (60k tracks)")

        assertEquals(provider.albums.size.toLong(), count("albums"))
        assertEquals(provider.tracks.size.toLong(), count("tracks"))
        goneAlbums.forEach { assertNull(runBlocking { db.albumDao().get(sid, it) }) }
        goneTracks.forEach { assertNull(runBlocking { db.trackDao().get(sid, it) }) }
        assertNotNull(runBlocking { db.trackDao().get(sid, "t-new-1") })
        // Nothing older than this sync is left, and deleting rows took their search entries along.
        assertEquals(0L, db.longValue("SELECT COUNT(*) FROM tracks WHERE serverId = ? AND syncGeneration < 2", sid))
        assertEquals(expectedHits(provider.tracks, "heart"), ftsHits("heart"))
        assertEquals(provider.tracks.count { it.starred }.toLong(), db.longValue("SELECT COUNT(*) FROM tracks WHERE serverId = ? AND starred = 1", sid))
    }

    @Test
    fun `rows a detail screen wrote during a sync survive its cleanup`() {
        val provider = LargeLibraryProvider(library)
        sync(provider, generation = 1, full = true, label = "sync: first full sync (setup)")
        // An album opened while the next sync runs is written with that sync's generation.
        val extra = Track(id = "t-opened", serverId = sid, title = "Opened Meanwhile", albumId = "al0")
        runBlocking { db.trackDao().upsert(extra.toEntity(generation = 2)) }
        sync(provider, generation = 2, full = true, label = "sync: second full sync, no changes (60k tracks)")
        assertNotNull(runBlocking { db.trackDao().get(sid, "t-opened") })
        assertEquals(library.tracks.size + 1L, count("tracks"))
    }

    @Test
    fun `an incremental sync reads new and retagged albums only, and deletes nothing`() {
        val provider = LargeLibraryProvider(library)
        sync(provider, generation = 1, full = true, label = "sync: first full sync (setup)")

        val newAlbum = Album(id = "al-inc", serverId = sid, name = "Late Arrival", artistId = "ar2", artistName = library.artists[2].name,
            createdAt = LargeLibrary.BASE_TIME + 86_400_000L)
        provider.addAlbum(newAlbum, (1..9).map { Track(id = "t-inc-$it", serverId = sid, title = "Arrival $it", albumId = newAlbum.id) })
        // Retag the most recent album: same track count, new artist name.
        val retagged = library.albums.maxBy { it.createdAt }
        provider.albums = provider.albums.map { if (it.id == retagged.id) it.copy(artistName = "Renamed Artist") else it }
        provider.tracks = provider.tracks.map { if (it.albumId == retagged.id) it.copy(artistName = "Renamed Artist") else it }
        val gone = library.albums.last { it.createdAt < retagged.createdAt - LargeLibrary.EIGHT_YEARS_MS / 2 }
        provider.removeAlbums(setOf(gone.id))

        val summary = sync(provider, generation = 2, full = false, label = "sync: incremental (200 recent albums)")

        assertFalse(summary.full)
        assertEquals(9 + retagged.trackCount, summary.tracks)
        assertEquals("Renamed Artist", runBlocking { db.trackDao().observeForAlbum(sid, retagged.id).first() }.first().artistName)
        assertNotNull(runBlocking { db.trackDao().get(sid, "t-inc-9") })
        // Only a full sync deletes; the removed album stays until then.
        assertNotNull(runBlocking { db.albumDao().get(sid, gone.id) })
        assertTrue(count("tracks") >= library.tracks.size.toLong())
    }
}
