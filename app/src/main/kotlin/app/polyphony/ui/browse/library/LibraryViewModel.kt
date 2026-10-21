package app.polyphony.ui.browse.library

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
import app.polyphony.core.domain.model.LibraryFilter
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
import kotlinx.collections.immutable.persistentListOf
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

/** What the Library screen needs before it can lay out its pager. */
data class LibraryStart(val tabs: ImmutableList<LibraryTab>, val defaultTab: LibraryTab)

enum class LibraryTab(val key: String) {
    ARTISTS("artists"), ALBUMS("albums"), SONGS("songs"), PLAYLISTS("playlists"), GENRES("genres");

    companion object {
        fun fromKey(key: String?): LibraryTab? = entries.firstOrNull { it.key == key }
    }
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val syncManager: LibrarySyncManager,
    private val settings: SettingsRepository,
    private val playback: PlaybackController,
) : ViewModel() {

    // ---- persisted view options (settings, not saved state: they must survive a cold start and an update) ----
    private val appearance = settings.settings.map { it.appearance }
    val artistSort: StateFlow<ArtistSort> = appearance.map { it.artistSort }.distinctUntilChanged().asState(ArtistSort.NAME)
    val albumSort: StateFlow<AlbumSort> = appearance.map { it.albumSort }.distinctUntilChanged().asState(AlbumSort.NAME)
    val albumAscending: StateFlow<Boolean> = appearance.map { it.albumAscending }.distinctUntilChanged().asState(true)
    val trackSort: StateFlow<TrackSort> = appearance.map { it.trackSort }.distinctUntilChanged().asState(TrackSort.TITLE)
    val trackAscending: StateFlow<Boolean> = appearance.map { it.trackAscending }.distinctUntilChanged().asState(true)
    val artistFilter: StateFlow<LibraryFilter> = appearance.map { it.artistFilter }.distinctUntilChanged().asState(LibraryFilter.NONE)
    val albumFilter: StateFlow<LibraryFilter> = appearance.map { it.albumFilter }.distinctUntilChanged().asState(LibraryFilter.NONE)
    val trackFilter: StateFlow<LibraryFilter> = appearance.map { it.trackFilter }.distinctUntilChanged().asState(LibraryFilter.NONE)
    val playlistFilter: StateFlow<LibraryFilter> = appearance.map { it.playlistFilter }.distinctUntilChanged().asState(LibraryFilter.NONE)
    /** Enabled Library tabs in the user's order (Appearance → Library tabs). */
    val libraryTabs: StateFlow<ImmutableList<LibraryTab>> = settings.settings
        .map { s -> s.appearance.libraryTabs.ifEmpty { LibrarySection.entries }.map { LibraryTab.valueOf(it.name) }.toImmutableList() }
        .distinctUntilChanged()
        .asState(LibraryTab.entries.toImmutableList())

    /** Tabs plus the one to open first; `null` until settings are loaded so the pager never starts on a guess. */
    val libraryStart: StateFlow<LibraryStart?> = settings.settings
        .map { s ->
            val tabs = s.appearance.libraryTabs.ifEmpty { LibrarySection.entries }.map { LibraryTab.valueOf(it.name) }
            val preferred = LibraryTab.valueOf(s.appearance.libraryDefaultTab.name)
            LibraryStart(tabs.toImmutableList(), if (preferred in tabs) preferred else tabs.first())
        }
        .distinctUntilChanged()
        .asState(null)

    // Layouts are real settings (Appearance → Library layout) so they persist and can be set per type.
    val artistsGrid: StateFlow<Boolean> = settings.settings.map { it.appearance.artistsLayout == LibraryLayout.GRID }.asState(false)
    val albumsGrid: StateFlow<Boolean> = settings.settings.map { it.appearance.albumsLayout == LibraryLayout.GRID }.asState(false)
    val playlistsGrid: StateFlow<Boolean> = settings.settings.map { it.appearance.playlistsLayout == LibraryLayout.GRID }.asState(false)

    fun setArtistSort(sort: ArtistSort) = updateAppearance { it.copy(artistSort = sort) }
    fun setAlbumSort(sort: AlbumSort, ascending: Boolean) = updateAppearance { it.copy(albumSort = sort, albumAscending = ascending) }
    fun setTrackSort(sort: TrackSort, ascending: Boolean) = updateAppearance { it.copy(trackSort = sort, trackAscending = ascending) }
    fun setArtistFilter(filter: LibraryFilter) = updateAppearance { it.copy(artistFilter = filter) }
    fun setAlbumFilter(filter: LibraryFilter) = updateAppearance { it.copy(albumFilter = filter) }
    fun setTrackFilter(filter: LibraryFilter) = updateAppearance { it.copy(trackFilter = filter) }
    fun setPlaylistFilter(filter: LibraryFilter) = updateAppearance { it.copy(playlistFilter = filter) }
    fun setArtistsGrid(grid: Boolean) = updateAppearance { it.copy(artistsLayout = grid.toLayout()) }
    fun setAlbumsGrid(grid: Boolean) = updateAppearance { it.copy(albumsLayout = grid.toLayout()) }
    fun setPlaylistsGrid(grid: Boolean) = updateAppearance { it.copy(playlistsLayout = grid.toLayout()) }

    private fun Boolean.toLayout() = if (this) LibraryLayout.GRID else LibraryLayout.LIST
    private fun updateAppearance(transform: (AppearanceSettings) -> AppearanceSettings) {
        viewModelScope.launch { settings.update { it.copy(appearance = transform(it.appearance)) } }
    }

    // ---- content (null = first load pending) ----
    val artists: StateFlow<ImmutableList<Artist>?> = combine(artistSort, artistFilter) { s, f -> s to f }
        .flatMapLatest { (sort, filter) -> library.artists(sort, filter) }
        .map { it.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val albums: StateFlow<ImmutableList<Album>?> = combine(albumSort, albumAscending, albumFilter) { s, a, f -> Triple(s, a, f) }
        .flatMapLatest { (sort, asc, filter) -> library.albums(sort, asc, filter) }
        .map { it.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val trackOrder = combine(trackSort, trackAscending, trackFilter) { s, a, f -> Triple(s, a, f) }

    /** Songs tab rows, paged from the cache: a 50k-track library costs the same as a 500-track one. */
    val pagedTracks: Flow<PagingData<Track>> = trackOrder
        .flatMapLatest { (sort, asc, filter) -> library.pagedTracks(sort, asc, filter) }
        .cachedIn(viewModelScope)

    /** Total for the Songs header, after filtering; `null` until the first count arrives. */
    val trackCount: StateFlow<Int?> = trackFilter
        .flatMapLatest { library.trackCount(it) }
        .map<Int, Int?> { it }
        .asState(null)

    /** Alphabet-rail positions for [pagedTracks], from sort keys only. */
    val trackLetters: StateFlow<LetterIndex> = trackOrder
        .flatMapLatest { (sort, asc, filter) -> library.trackLetterIndex(sort, asc, filter) }
        .asState(LetterIndex.EMPTY)

    val playlists: StateFlow<ImmutableList<Playlist>?> = playlistFilter
        .flatMapLatest { library.playlists(it) }
        .map { it.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val genres: StateFlow<ImmutableList<Genre>?> = library.genres()
        .map { it.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Choices for the Genre filter chip. */
    val genreNames: StateFlow<ImmutableList<String>> = library.genres()
        .map { list -> list.map { it.name }.toImmutableList() }
        .asState(persistentListOf())

    /** The library's year span, for the Year filter chip. */
    val yearRange: StateFlow<IntRange?> = library.yearRange().asState(null)

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
            val slice = library.tracksSlice(trackSort.value, trackAscending.value, offset = index, limit = QUEUE_WINDOW, filter = trackFilter.value)
            // In offline mode the list is filtered after paging, so a list index is not a DB offset:
            // anchor the queue on the song that was tapped either way.
            val queue = if (slice.firstOrNull()?.id == track.id) slice else listOf(track) + slice.filter { it.id != track.id }
            playback.playTracks(queue, 0, queueTitle = "Songs")
        }
    }

    fun playAllSongs(shuffle: Boolean) {
        val filter = trackFilter.value
        if (shuffle && !filter.isActive) {
            shuffleLibrary()
            return
        }
        viewModelScope.launch {
            if (shuffle) {
                // A filtered list shuffles only what it shows, from the cache.
                val picks = library.randomCachedTracks(SHUFFLE_CAP, filter)
                if (picks.isNotEmpty()) playback.playTracks(picks, 0, shuffle = false, queueTitle = "Songs")
                return@launch
            }
            val list = library.tracksSlice(trackSort.value, trackAscending.value, offset = 0, limit = QUEUE_WINDOW, filter = filter)
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
        const val SHUFFLE_CAP = 300

        /** Longest queue the Songs tab builds from a tap or "Play all". */
        const val QUEUE_WINDOW = 500
    }
}
