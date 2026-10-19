package app.polyphony.core.data.sync

import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Artist
import app.polyphony.core.domain.model.Genre
import app.polyphony.core.domain.model.Playlist
import app.polyphony.core.domain.model.StarredContent
import app.polyphony.core.domain.model.Track

/** Where the sync engine writes. Abstracted so the engine is unit-testable without Room. */
interface SyncSink {
    suspend fun writeArtists(serverId: String, artists: List<Artist>, generation: Long)
    suspend fun writeAlbums(serverId: String, albums: List<Album>, generation: Long)
    suspend fun writeTracks(serverId: String, tracks: List<Track>, generation: Long)
    /** Upserts the playlist, its tracks, and replaces its membership. */
    suspend fun writePlaylist(serverId: String, playlist: Playlist, tracks: List<Track>, generation: Long)
    suspend fun deletePlaylistsExcept(serverId: String, keepIds: Collection<String>)
    suspend fun applyStarred(serverId: String, starred: StarredContent, generation: Long)
    suspend fun writeGenres(serverId: String, genres: List<Genre>, generation: Long)
    /** Album id → track count for everything currently cached (used by incremental sync). */
    suspend fun albumTrackCounts(serverId: String): Map<String, Int>
    /** Delete artists/albums/tracks/genres whose generation is older than [generation]. */
    suspend fun deleteStale(serverId: String, generation: Long)
}
