package app.polyphony.core.domain.repository

import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.AlbumSort
import app.polyphony.core.domain.model.Artist
import app.polyphony.core.domain.model.ArtistSort
import app.polyphony.core.domain.model.Genre
import app.polyphony.core.domain.model.ItemKind
import app.polyphony.core.domain.model.LetterIndex
import app.polyphony.core.domain.model.LibraryFilter
import app.polyphony.core.domain.model.Lyrics
import app.polyphony.core.domain.model.Playlist
import app.polyphony.core.domain.model.SearchResults
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.model.TrackSort
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The app's single source of truth for library content. Reads are Room-backed Flows scoped to the
 * active server, so browsing is instant and works offline. Mutations apply optimistically to the
 * local cache and then propagate to the server.
 */
interface LibraryRepository {
    // ---- Artists ----
    fun artists(sort: ArtistSort = ArtistSort.NAME, filter: LibraryFilter = LibraryFilter.NONE): Flow<List<Artist>>
    fun artist(id: String): Flow<Artist?>
    /**
     * What you like, per the [FeedbackRepository] mode in effect: favourites, or in Ratings mode
     * everything rated at least `Feedback.topRatedMinimum` (best first). Same for albums and tracks.
     */
    fun likedArtists(): Flow<List<Artist>>

    // ---- Albums ----
    fun albums(sort: AlbumSort = AlbumSort.NAME, ascending: Boolean = true, filter: LibraryFilter = LibraryFilter.NONE): Flow<List<Album>>
    fun album(id: String): Flow<Album?>
    fun albumsByArtist(artistId: String): Flow<List<Album>>
    fun albumsByGenre(genre: String): Flow<List<Album>>
    fun likedAlbums(): Flow<List<Album>>
    fun recentlyAddedAlbums(limit: Int = 20): Flow<List<Album>>
    fun recentlyPlayedAlbums(limit: Int = 20): Flow<List<Album>>
    fun mostPlayedAlbums(limit: Int = 20): Flow<List<Album>>
    fun randomAlbums(limit: Int = 20, seed: Long): Flow<List<Album>>

    // ---- Tracks ----
    fun tracks(sort: TrackSort = TrackSort.TITLE, ascending: Boolean = true, filter: LibraryFilter = LibraryFilter.NONE): Flow<List<Track>>

    /**
     * Every track on the active server, paged straight out of the cache. Use this for the Songs tab
     * and anything else that can show tens of thousands of rows: it never materialises the whole
     * list, and a write to the tracks table only reloads the pages on screen.
     */
    fun pagedTracks(
        sort: TrackSort = TrackSort.TITLE,
        ascending: Boolean = true,
        filter: LibraryFilter = LibraryFilter.NONE,
    ): Flow<PagingData<Track>>

    /** Number of tracks [pagedTracks] will show for [filter] (after offline filtering). */
    fun trackCount(filter: LibraryFilter = LibraryFilter.NONE): Flow<Int>

    /** Alphabet-rail positions for [pagedTracks] with the same sort and filter, built from sort keys only. */
    fun trackLetterIndex(
        sort: TrackSort = TrackSort.TITLE,
        ascending: Boolean = true,
        filter: LibraryFilter = LibraryFilter.NONE,
    ): Flow<LetterIndex>

    /** [limit] tracks from [offset] in the same order as [pagedTracks]: builds a queue without loading the whole list. */
    suspend fun tracksSlice(
        sort: TrackSort,
        ascending: Boolean,
        offset: Int,
        limit: Int,
        filter: LibraryFilter = LibraryFilter.NONE,
    ): List<Track>

    /** A random sample of the active server's cached tracks matching [filter], for shuffling without the server. */
    suspend fun randomCachedTracks(limit: Int, filter: LibraryFilter = LibraryFilter.NONE): List<Track>
    fun track(id: String): Flow<Track?>
    fun tracksForAlbum(albumId: String): Flow<List<Track>>
    fun tracksForArtist(artistId: String): Flow<List<Track>>
    fun tracksByGenre(genre: String): Flow<List<Track>>
    fun likedTracks(): Flow<List<Track>>
    fun recentlyPlayedTracks(limit: Int = 50): Flow<List<Track>>
    suspend fun getTrack(id: String): Track?
    suspend fun getTracks(ids: List<String>): List<Track>

    // ---- Playlists ----
    fun playlists(filter: LibraryFilter = LibraryFilter.NONE): Flow<List<Playlist>>
    fun playlist(id: String): Flow<Playlist?>
    fun tracksForPlaylist(playlistId: String): Flow<List<Track>>

    // ---- Genres ----
    fun genres(): Flow<List<Genre>>

    /** Earliest and latest album year in the cache, for the year filter; `null` when no album has one. */
    fun yearRange(): Flow<IntRange?>

    // ---- Search ----
    /**
     * Local, instant search over the cached library, narrowed by [filter] in SQL so the limit
     * still fills. Its `downloadedOnly` keeps only what is on the phone (the Downloaded search
     * scope), whether or not offline mode is on. A section the filter cannot apply to (a rating
     * filter and artists' genres, see [LibraryFilter.excludesAll]) comes back empty.
     */
    fun searchLocal(query: String, limit: Int = 30, filter: LibraryFilter = LibraryFilter.NONE): Flow<SearchResults>
    /** Remote search for things not yet synced. */
    suspend fun searchRemote(query: String): SearchResults

    /** Drop an album and its tracks from this phone's copy of the library (it left the server). */
    suspend fun forgetAlbum(id: String)

    // ---- On-demand refresh from the server (fills the cache) ----
    suspend fun refreshArtist(id: String)
    suspend fun refreshAlbum(id: String)
    suspend fun refreshPlaylist(id: String)
    suspend fun refreshPlaylists()
    suspend fun refreshStarred()

    // ---- Remote-only reads (not cached) ----
    suspend fun getArtistTopTracks(artist: Artist, count: Int = 20): List<Track>
    suspend fun getSimilarArtists(artistId: String): List<Artist>

    /** Albums by others with a track crediting [artist] ("Appears on"), newest first. */
    suspend fun albumsAppearingOn(artist: Artist): List<Album> = emptyList()
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
