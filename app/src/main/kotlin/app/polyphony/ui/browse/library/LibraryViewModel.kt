package app.polyphony.ui.browse.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.polyphony.core.domain.model.LetterIndex
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.AlbumSort
import app.polyphony.core.domain.model.Artist
import app.polyphony.core.domain.model.ArtistSort
import app.polyphony.core.domain.model.Genre
import app.polyphony.core.domain.model.Playlist
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.model.TrackSort
import app.polyphony.core.domain.playback.PlaybackController
import app.polyphony.core.domain.playback.PlaybackState
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.LibrarySyncManager
import app.polyphony.core.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import app.polyphony.core.domain.repository.LibrarySection
import app.polyphony.core.domain.repository.AppearanceSettings
import app.polyphony.core.domain.repository.LibraryLayout
import app.polyphony.core.domain.repository.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LibraryTab(val key: String) {
    ARTISTS("artists"), ALBUMS("albums"), SONGS("songs"), PLAYLISTS("playlists"), GENRES("genres");

    companion object {
        fun fromKey(key: String?): LibraryTab = entries.firstOrNull { it.key == key } ?: ARTISTS
    }
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val syncManager: LibrarySyncManager,
    private val settings: SettingsRepository,
    private val playback: PlaybackController,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    // ---- persisted view options ----
    val artistSort: StateFlow<ArtistSort> = savedState.getStateFlow(KEY_ARTIST_SORT, ArtistSort.NAME.name).map { ArtistSort.valueOf(it) }.asState(ArtistSort.NAME)
    val albumSort: StateFlow<AlbumSort> = savedState.getStateFlow(KEY_ALBUM_SORT, AlbumSort.NAME.name).map { AlbumSort.valueOf(it) }.asState(AlbumSort.NAME)
    val albumAscending: StateFlow<Boolean> = savedState.getStateFlow(KEY_ALBUM_ASC, true)
    val trackSort: StateFlow<TrackSort> = savedState.getStateFlow(KEY_TRACK_SORT, TrackSort.TITLE.name).map { TrackSort.valueOf(it) }.asState(TrackSort.TITLE)
    val trackAscending: StateFlow<Boolean> = savedState.getStateFlow(KEY_TRACK_ASC, true)
    /** Enabled Library tabs in the user's order (Appearance → Library tabs). */
    val libraryTabs: StateFlow<ImmutableList<LibraryTab>> = settings.settings
        .map { s -> s.appearance.libraryTabs.ifEmpty { LibrarySection.entries }.map { LibraryTab.valueOf(it.name) }.toImmutableList() }
        .distinctUntilChanged()
        .asState(LibraryTab.entries.toImmutableList())

    // Layouts are real settings (Appearance → Library layout) so they persist and can be set per type.
    val artistsGrid: StateFlow<Boolean> = settings.settings.map { it.appearance.artistsLayout == LibraryLayout.GRID }.asState(false)
    val albumsGrid: StateFlow<Boolean> = settings.settings.map { it.appearance.albumsLayout == LibraryLayout.GRID }.asState(false)
    val playlistsGrid: StateFlow<Boolean> = settings.settings.map { it.appearance.playlistsLayout == LibraryLayout.GRID }.asState(false)

    fun setArtistSort(sort: ArtistSort) { savedState[KEY_ARTIST_SORT] = sort.name }
    fun setAlbumSort(sort: AlbumSort, ascending: Boolean) { savedState[KEY_ALBUM_SORT] = sort.name; savedState[KEY_ALBUM_ASC] = ascending }
    fun setTrackSort(sort: TrackSort, ascending: Boolean) { savedState[KEY_TRACK_SORT] = sort.name; savedState[KEY_TRACK_ASC] = ascending }
    fun setArtistsGrid(grid: Boolean) = updateAppearance { it.copy(artistsLayout = grid.toLayout()) }
    fun setAlbumsGrid(grid: Boolean) = updateAppearance { it.copy(albumsLayout = grid.toLayout()) }
    fun setPlaylistsGrid(grid: Boolean) = updateAppearance { it.copy(playlistsLayout = grid.toLayout()) }

    private fun Boolean.toLayout() = if (this) LibraryLayout.GRID else LibraryLayout.LIST
    private fun updateAppearance(transform: (AppearanceSettings) -> AppearanceSettings) {
        viewModelScope.launch { settings.update { it.copy(appearance = transform(it.appearance)) } }
    }

    // ---- content (null = first load pending) ----
    val artists: StateFlow<ImmutableList<Artist>?> = artistSort
        .flatMapLatest { library.artists(it) }
        .map { it.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val albums: StateFlow<ImmutableList<Album>?> = combine(albumSort, albumAscending) { s, a -> s to a }
        .flatMapLatest { (sort, asc) -> library.albums(sort, asc) }
        .map { it.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val trackOrder = combine(trackSort, trackAscending) { s, a -> s to a }

    /** Songs tab rows, paged from the cache: a 50k-track library costs the same as a 500-track one. */
    val pagedTracks: Flow<PagingData<Track>> = trackOrder
        .flatMapLatest { (sort, asc) -> library.pagedTracks(sort, asc) }
        .cachedIn(viewModelScope)

    /** Total for the Songs header; `null` until the first count arrives. */
    val trackCount: StateFlow<Int?> = library.trackCount().map<Int, Int?> { it }.asState(null)

    /** Alphabet-rail positions for [pagedTracks], from sort keys only. */
    val trackLetters: StateFlow<LetterIndex> = trackOrder
        .flatMapLatest { (sort, asc) -> library.trackLetterIndex(sort, asc) }
        .asState(LetterIndex.EMPTY)

    val playlists: StateFlow<ImmutableList<Playlist>?> = library.playlists()
        .map { it.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val genres: StateFlow<ImmutableList<Genre>?> = library.genres()
        .map { it.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val gridColumns: StateFlow<Int> = settings.settings.map { it.appearance.gridColumns }.asState(0)
    val showTrackNumbers: StateFlow<Boolean> = settings.settings.map { it.appearance.showTrackNumbers }.asState(true)
    val playbackState: StateFlow<PlaybackState> = playback.state
    val syncState: StateFlow<SyncState> = syncManager.state

    // ---- actions ----
    /**
     * Plays the song at [index] of the paged Songs list. The queue is that song plus the next
     * [QUEUE_WINDOW] in the current order, not the whole library, so a 50k-track collection never
     * becomes a 50k-item queue.
     */
    fun playTrackAt(index: Int, track: Track) {
        viewModelScope.launch {
            val slice = library.tracksSlice(trackSort.value, trackAscending.value, offset = index, limit = QUEUE_WINDOW)
            // In offline mode the list is filtered after paging, so a list index is not a DB offset:
            // anchor the queue on the song that was tapped either way.
            val queue = if (slice.firstOrNull()?.id == track.id) slice else listOf(track) + slice.filter { it.id != track.id }
            playback.playTracks(queue, 0, queueTitle = "Songs")
        }
    }

    fun playAllSongs(shuffle: Boolean) {
        if (shuffle) {
            shuffleLibrary()
            return
        }
        viewModelScope.launch {
            val list = library.tracksSlice(trackSort.value, trackAscending.value, offset = 0, limit = QUEUE_WINDOW)
            if (list.isNotEmpty()) playback.playTracks(list, 0, queueTitle = "Songs")
        }
    }

    /** Shuffle the whole library: a server-side random pick when online, a cached random sample otherwise. */
    fun shuffleLibrary() {
        viewModelScope.launch {
            val remote = runCatching { library.getRandomTracks(SHUFFLE_CAP) }.getOrDefault(emptyList())
            val picks = remote.ifEmpty { library.randomCachedTracks(SHUFFLE_CAP) }
            if (picks.isNotEmpty()) playback.playTracks(picks, 0, shuffle = false, queueTitle = "Shuffle all")
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch { runCatching { library.createPlaylist(name) } }
    }

    fun refresh() {
        viewModelScope.launch { runCatching { syncManager.syncNow(false) } }
    }

    private fun <T> kotlinx.coroutines.flow.Flow<T>.asState(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    private companion object {
        const val KEY_ARTIST_SORT = "artistSort"
        const val KEY_ALBUM_SORT = "albumSort"
        const val KEY_ALBUM_ASC = "albumAsc"
        const val KEY_TRACK_SORT = "trackSort"
        const val KEY_TRACK_ASC = "trackAsc"
        const val SHUFFLE_CAP = 300

        /** Longest queue the Songs tab builds from a tap or "Play all". */
        const val QUEUE_WINDOW = 500
    }
}
