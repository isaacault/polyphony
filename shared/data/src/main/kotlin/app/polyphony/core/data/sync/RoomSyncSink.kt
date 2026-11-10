package app.polyphony.core.data.sync

import androidx.room.withTransaction
import app.polyphony.core.data.db.PolyphonyDatabase
import app.polyphony.core.data.db.entity.AlbumEntity
import app.polyphony.core.data.db.entity.ArtistEntity
import app.polyphony.core.data.db.entity.PlaylistTrackEntity
import app.polyphony.core.data.db.entity.TrackEntity
import app.polyphony.core.data.mappers.toEntity
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Artist
import app.polyphony.core.domain.model.Genre
import app.polyphony.core.domain.model.Playlist
import app.polyphony.core.domain.model.StarredContent
import app.polyphony.core.domain.model.Track
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomSyncSink @Inject constructor(private val db: PolyphonyDatabase) : SyncSink {

    override suspend fun writeArtists(serverId: String, artists: List<Artist>, generation: Long) {
        if (artists.isEmpty()) return
        db.withTransaction { writeArtistRows(serverId, artists.map { it.toEntity(generation) }) }
    }

    override suspend fun writeAlbums(serverId: String, albums: List<Album>, generation: Long) {
        if (albums.isEmpty()) return
        db.withTransaction { writeAlbumRows(serverId, albums.map { it.toEntity(generation) }) }
    }

    override suspend fun writeTracks(serverId: String, tracks: List<Track>, generation: Long) {
        if (tracks.isEmpty()) return
        db.withTransaction { writeTrackRows(serverId, tracks.map { it.toEntity(generation) }) }
    }

    override suspend fun writePlaylist(serverId: String, playlist: Playlist, tracks: List<Track>, generation: Long) {
        db.withTransaction {
            db.playlistDao().upsert(playlist.copy(trackCount = tracks.size).toEntity(generation))
            writeTrackRows(serverId, tracks.map { it.toEntity(generation) })
            db.playlistDao().replaceMembers(
                serverId,
                playlist.id,
                tracks.mapIndexed { index, t -> PlaylistTrackEntity(serverId, playlist.id, t.id, index) },
            )
        }
    }

    override suspend fun deletePlaylistsExcept(serverId: String, keepIds: Collection<String>) {
        db.withTransaction {
            if (keepIds.isEmpty()) {
                db.playlistDao().deleteAllMembers(serverId)
                db.playlistDao().deleteByServer(serverId)
            } else {
                db.playlistDao().deleteMembersExcept(serverId, keepIds.toList())
                db.playlistDao().deleteExcept(serverId, keepIds.toList())
            }
        }
    }

    override suspend fun applyStarred(serverId: String, starred: StarredContent, generation: Long) {
        db.withTransaction {
            db.artistDao().clearStarred(serverId)
            db.albumDao().clearStarred(serverId)
            db.trackDao().clearStarred(serverId)
            writeArtistRows(serverId, starred.artists.map { it.copy(starred = true).toEntity(generation) })
            writeAlbumRows(serverId, starred.albums.map { it.copy(starred = true).toEntity(generation) })
            writeTrackRows(serverId, starred.tracks.map { it.copy(starred = true).toEntity(generation) })
        }
    }

    override suspend fun writeGenres(serverId: String, genres: List<Genre>, generation: Long) {
        db.withTransaction {
            if (genres.isNotEmpty()) db.genreDao().upsertAll(genres.map { it.toEntity(generation) })
            db.genreDao().deleteStale(serverId, generation)
        }
    }

    override suspend fun albumTrackCounts(serverId: String): Map<String, Int> =
        db.albumDao().getTrackCounts(serverId).associate { it.id to it.trackCount }

    override suspend fun albumNames(serverId: String): Map<String, Pair<String, String?>> =
        db.albumDao().getNames(serverId).associate { it.id to (it.name to it.artistName) }

    override suspend fun deleteStale(serverId: String, generation: Long) {
        db.withTransaction {
            db.trackDao().deleteStale(serverId, generation)
            db.albumDao().deleteStale(serverId, generation)
            db.artistDao().deleteStale(serverId, generation)
            db.genreDao().deleteStale(serverId, generation)
        }
    }

    private suspend fun writeArtistRows(serverId: String, rows: List<ArtistEntity>) {
        val dao = db.artistDao()
        writeAll(serverId, rows, ArtistEntity::id, dao::existingIds, dao::insertAll, dao::updateAll)
    }

    private suspend fun writeAlbumRows(serverId: String, rows: List<AlbumEntity>) {
        val dao = db.albumDao()
        writeAll(serverId, rows, AlbumEntity::id, dao::existingIds, dao::insertAll, dao::updateAll)
    }

    private suspend fun writeTrackRows(serverId: String, rows: List<TrackEntity>) {
        val dao = db.trackDao()
        writeAll(serverId, rows, TrackEntity::id, dao::existingIds, dao::insertAll, dao::updateAll)
    }

    /**
     * Upserts [rows] a chunk at a time, inside the caller's transaction: one query finds which
     * already exist, then the new rows are inserted and the others updated. `@Upsert` tries an
     * insert first and catches the constraint error for every row that exists, which on a re-sync
     * is every row. A row repeated within a chunk is written once, its last copy winning.
     */
    private suspend fun <E> writeAll(
        serverId: String,
        rows: List<E>,
        id: (E) -> String,
        existingIds: suspend (String, List<String>) -> List<String>,
        insert: suspend (List<E>) -> Unit,
        update: suspend (List<E>) -> Unit,
    ) {
        rows.chunked(CHUNK).forEach { chunk ->
            val unique = chunk.associateBy(id).values.toList()
            val existing = existingIds(serverId, unique.map(id)).toHashSet()
            val (present, fresh) = unique.partition { id(it) in existing }
            if (fresh.isNotEmpty()) insert(fresh)
            if (present.isNotEmpty()) update(present)
        }
    }

    private companion object {
        const val CHUNK = 500
    }
}
