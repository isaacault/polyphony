package app.polyphony.core.domain.repository

import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.AlbumSort
import app.polyphony.core.domain.model.Artist
import app.polyphony.core.domain.model.ArtistSort
import app.polyphony.core.domain.model.Genre
import app.polyphony.core.domain.model.ItemKind
import app.polyphony.core.domain.model.Lyrics
import app.polyphony.core.domain.model.Playlist
import app.polyphony.core.domain.model.SearchResults
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.model.TrackSort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The app's single source of truth for library content. Reads are Room-backed Flows scoped to the
 * active server, so browsing is instant and works offline. Mutations apply optimistically to the
 * local cache and then propagate to the server.
 */
interface LibraryRepository {
    // ---- Artists ----
    fun artists(sort: ArtistSort = ArtistSort.NAME): Flow<List<Artist>>
    fun artist(id: String): Flow<Artist?>
    fun starredArtists(): Flow<List<Artist>>

    // ---- Albums ----
    fun albums(sort: AlbumSort = AlbumSort.NAME, ascending: Boolean = true): Flow<List<Album>>
    fun album(id: String): Flow<Album?>
    fun albumsByArtist(artistId: String): Flow<List<Album>>
    fun albumsByGenre(genre: String): Flow<List<Album>>
    fun starredAlbums(): Flow<List<Album>>
    fun recentlyAddedAlbums(limit: Int = 20): Flow<List<Album>>
    fun recentlyPlayedAlbums(limit: Int = 20): Flow<List<Album>>
    fun mostPlayedAlbums(limit: Int = 20): Flow<List<Album>>
    fun randomAlbums(limit: Int = 20, seed: Long): Flow<List<Album>>

    // ---- Tracks ----
    fun tracks(sort: TrackSort = TrackSort.TITLE, ascending: Boolean = true): Flow<List<Track>>
    fun track(id: String): Flow<Track?>
    fun tracksForAlbum(albumId: String): Flow<List<Track>>
    fun tracksForArtist(artistId: String): Flow<List<Track>>
    fun tracksByGenre(genre: String): Flow<List<Track>>
    fun starredTracks(): Flow<List<Track>>
    fun recentlyPlayedTracks(limit: Int = 50): Flow<List<Track>>
    suspend fun getTrack(id: String): Track?
    suspend fun getTracks(ids: List<String>): List<Track>

    // ---- Playlists ----
    fun playlists(): Flow<List<Playlist>>
    fun playlist(id: String): Flow<Playlist?>
    fun tracksForPlaylist(playlistId: String): Flow<List<Track>>

    // ---- Genres ----
    fun genres(): Flow<List<Genre>>

    // ---- Search ----
    /** Local, instant search over the cached library. */
    fun searchLocal(query: String, limit: Int = 30): Flow<SearchResults>
    /** Remote search for things not yet synced. */
    suspend fun searchRemote(query: String): SearchResults

    // ---- On-demand refresh from the server (fills the cache) ----
    suspend fun refreshArtist(id: String)
    suspend fun refreshAlbum(id: String)
    suspend fun refreshPlaylist(id: String)
    suspend fun refreshPlaylists()
    suspend fun refreshStarred()

    // ---- Remote-only reads (not cached) ----
    suspend fun getArtistTopTracks(artist: Artist, count: Int = 20): List<Track>
    suspend fun getSimilarArtists(artistId: String): List<Artist>
    suspend fun getSimilarTracks(trackId: String, count: Int = 50): List<Track>
    suspend fun getRandomTracks(size: Int = 50, genre: String? = null): List<Track>
    suspend fun getLyrics(track: Track): Lyrics?

    // ---- Mutations ----
    suspend fun setStarred(id: String, kind: ItemKind, starred: Boolean)
    suspend fun setRating(id: String, kind: ItemKind, rating: Int)
    suspend fun createPlaylist(name: String, trackIds: List<String> = emptyList()): Playlist
    suspend fun renamePlaylist(id: String, name: String)
    suspend fun addToPlaylist(playlistId: String, trackIds: List<String>)
    suspend fun removeFromPlaylist(playlistId: String, indices: List<Int>)
    suspend fun deletePlaylist(id: String)
    /** Record a local play so "recently played" works before the server catches up. */
    suspend fun recordPlay(track: Track, playedAtEpochMs: Long)
}

/** Full/incremental library synchronisation into the local cache. */
interface LibrarySyncManager {
    val state: StateFlow<SyncState>
    suspend fun syncNow(full: Boolean = false)
    fun scheduleAutoSync(intervalHours: Int, wifiOnly: Boolean)
    fun cancelAutoSync()
}

data class SyncState(
    val status: Status = Status.IDLE,
    /** 0f..1f, or null when indeterminate. */
    val progress: Float? = null,
    val phase: String? = null,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
) {
    enum class Status { IDLE, RUNNING, FAILED }
    val isRunning: Boolean get() = status == Status.RUNNING
}
