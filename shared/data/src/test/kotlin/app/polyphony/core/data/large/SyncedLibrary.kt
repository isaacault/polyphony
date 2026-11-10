package app.polyphony.core.data.large

import androidx.room.withTransaction
import app.polyphony.core.data.db.PolyphonyDatabase
import app.polyphony.core.data.db.entity.PlayContextEntity
import app.polyphony.core.data.db.entity.PlayHistoryEntity
import app.polyphony.core.data.sync.LibrarySyncEngine
import app.polyphony.core.data.sync.RoomSyncSink
import kotlinx.coroutines.runBlocking
import kotlin.math.pow
import kotlin.random.Random

/**
 * The [LargeLibrary] synced into one in-memory database through the real engine and sink, plus a
 * full listening history (5,000 plays, the cap `recordPlay` prunes to) and fifty "Continue
 * listening" contexts. Built once per test JVM and shared by the read-only query tests.
 */
object SyncedLibrary {
    const val GENERATION = 1L
    const val HISTORY = 5_000

    val library: LargeLibrary by lazy { LargeLibrary() }
    val serverId: String get() = library.serverId

    val db: PolyphonyDatabase by lazy {
        val db = TestDatabase.inMemory()
        val provider = LargeLibraryProvider(library)
        Timings.once("fixture: full sync into an empty database") {
            LibrarySyncEngine().run(provider, RoomSyncSink(db), GENERATION, full = true) { _, _ -> }
        }
        runBlocking { addListening(db) }
        db
    }

    /** Track ids in the order they were last played, most recent first (the history's truth). */
    val playedMostRecentFirst: List<String> by lazy { history.sortedByDescending { it.playedAt }.map { it.trackId }.distinct() }

    /** The plays written to `play_history`. */
    val history: List<PlayHistoryEntity> by lazy {
        val random = Random(3)
        val tracks = library.tracks
        // A few favourites played over and over, then a long tail.
        List(HISTORY) { i ->
            val track = tracks[((random.nextDouble().pow(3)) * tracks.size).toInt()]
            PlayHistoryEntity(serverId = serverId, trackId = track.id, albumId = track.albumId, playedAt = LargeLibrary.BASE_TIME - i * 7L * 60_000)
        }
    }

    private suspend fun addListening(db: PolyphonyDatabase) {
        db.withTransaction {
            history.forEach { db.playHistoryDao().insert(it) }
            library.albums.take(50).forEachIndexed { i, album ->
                db.playContextDao().upsert(
                    PlayContextEntity(
                        key = "$serverId:ALBUM:${album.id}", serverId = serverId, kind = "ALBUM", contextId = album.id,
                        title = album.name, subtitle = album.artistName, artworkUrl = null, trackId = null, trackTitle = null,
                        trackIndex = 0, positionMs = 0, shuffle = false, updatedAt = LargeLibrary.BASE_TIME - i * 3_600_000L,
                    ),
                )
            }
        }
    }
}
