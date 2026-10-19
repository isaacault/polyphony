package app.polyphony.core.data.sync

import androidx.room.withTransaction
import app.polyphony.core.data.db.PolyphonyDatabase
import app.polyphony.core.data.db.entity.PlaylistTrackEntity
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
        db.withTransaction {
            artists.chunked(CHUNK).forEach { db.artistDao().upsertAll(it.map { a -> a.toEntity(generation) }) }
        }
    }

    override suspend fun writeAlbums(serverId: String, albums: List<Album>, generation: Long) {
        if (albums.isEmpty()) return
        db.withTransaction {
            albums.chunked(CHUNK).forEach { db.albumDao().upsertAll(it.map { a -> a.toEntity(generation) }) }
        }
    }

    override suspend fun writeTracks(serverId: String, tracks: List<Track>, generation: Long) {
        if (tracks.isEmpty()) return
        db.withTransaction {
            tracks.chunked(CHUNK).forEach { db.trackDao().upsertAll(it.map { t -> t.toEntity(generation) }) }
        }
    }

    override suspend fun writePlaylist(serverId: String, playlist: Playlist, tracks: List<Track>, generation: Long) {
        db.withTransaction {
            db.playlistDao().upsert(playlist.copy(trackCount = tracks.size).toEntity(generation))
            tracks.chunked(CHUNK).forEach { db.trackDao().upsertAll(it.map { t -> t.toEntity(generation) }) }
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
            starred.artists.chunked(CHUNK).forEach { db.artistDao().upsertAll(it.map { a -> a.copy(starred = true).toEntity(generation) }) }
            starred.albums.chunked(CHUNK).forEach { db.albumDao().upsertAll(it.map { a -> a.copy(starred = true).toEntity(generation) }) }
            starred.tracks.chunked(CHUNK).forEach { db.trackDao().upsertAll(it.map { t -> t.copy(starred = true).toEntity(generation) }) }
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

    override suspend fun deleteStale(serverId: String, generation: Long) {
        db.withTransaction {
            db.trackDao().deleteStale(serverId, generation)
            db.albumDao().deleteStale(serverId, generation)
            db.artistDao().deleteStale(serverId, generation)
            db.genreDao().deleteStale(serverId, generation)
        }
    }

    private companion object {
        const val CHUNK = 500
    }
}
