package app.polyphony.ui.browse.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Genre
import app.polyphony.core.domain.model.Playlist
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.playback.PlaybackController
import app.polyphony.core.domain.repository.DownloadRepository
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.LibrarySyncManager
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.core.domain.repository.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

data class HomeUiState(
    val isLoaded: Boolean = false,
    val servers: ImmutableList<Server> = persistentListOf(),
    val activeServer: Server? = null,
    val continueListening: ImmutableList<Track> = persistentListOf(),
    val recentlyAdded: ImmutableList<Album> = persistentListOf(),
    val mostPlayed: ImmutableList<Album> = persistentListOf(),
    val randomPicks: ImmutableList<Album> = persistentListOf(),
    val favorites: ImmutableList<Album> = persistentListOf(),
    val playlists: ImmutableList<Playlist> = persistentListOf(),
    val genres: ImmutableList<Genre> = persistentListOf(),
    val sync: SyncState = SyncState(),
    val isRefreshing: Boolean = false,
    val downloadedCount: Int = 0,
    val downloadedBytes: Long = 0L,
) {
    val isLibraryEmpty: Boolean
        get() = isLoaded && recentlyAdded.isEmpty() && playlists.isEmpty() && continueListening.isEmpty()
}

private data class Shelves(
    val continueListening: List<Track>,
    val recentlyAdded: List<Album>,
    val mostPlayed: List<Album>,
    val randomPicks: List<Album>,
    val favorites: List<Album>,
)

private data class Meta(
    val playlists: List<Playlist>,
    val genres: List<Genre>,
    val servers: List<Server>,
    val activeServer: Server?,
    val sync: SyncState,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val syncManager: LibrarySyncManager,
    private val servers: ServerRepository,
    private val settings: SettingsRepository,
    private val playback: PlaybackController,
    downloads: DownloadRepository,
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)
    private val downloadSummary = combine(downloads.downloadedTrackIds(), downloads.storageUsage) { ids, usage -> ids.size to usage.usedBytes }
        .distinctUntilChanged()

    private val randomPicks = settings.settings
        .map { it.library.randomAlbumSeed }
        .distinctUntilChanged()
        .flatMapLatest { seed -> library.randomAlbums(12, seed) }

    private val shelves = combine(
        library.recentlyPlayedTracks(20),
        library.recentlyAddedAlbums(20),
        library.mostPlayedAlbums(20),
        randomPicks,
        library.starredAlbums(),
    ) { recent, added, played, random, starred -> Shelves(recent, added, played, random, starred.take(20)) }

    private val meta = combine(
        library.playlists(),
        library.genres(),
        servers.servers,
        servers.activeServer,
        syncManager.state,
    ) { playlists, genres, serverList, active, sync -> Meta(playlists, genres, serverList, active, sync) }

    val state: StateFlow<HomeUiState> = combine(shelves, meta, refreshing, downloadSummary) { s, m, r, (dlCount, dlBytes) ->
        HomeUiState(
            downloadedCount = dlCount,
            downloadedBytes = dlBytes,
            isLoaded = true,
            servers = m.servers.toImmutableList(),
            activeServer = m.activeServer,
            continueListening = s.continueListening.toImmutableList(),
            recentlyAdded = s.recentlyAdded.toImmutableList(),
            mostPlayed = s.mostPlayed.toImmutableList(),
            randomPicks = s.randomPicks.toImmutableList(),
            favorites = s.favorites.toImmutableList(),
            playlists = m.playlists.toImmutableList(),
            genres = m.genres.toImmutableList(),
            sync = m.sync,
            isRefreshing = r,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun refresh(full: Boolean = false) {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            try {
                runCatching { syncManager.syncNow(full) }
            } finally {
                refreshing.value = false
            }
        }
    }

    fun reshuffleRandom() {
        viewModelScope.launch {
            settings.update { it.copy(library = it.library.copy(randomAlbumSeed = Random.nextLong())) }
        }
    }

    fun switchServer(serverId: String) {
        viewModelScope.launch { servers.setActive(serverId) }
    }

    fun playContinueListening(index: Int) {
        val tracks = state.value.continueListening
        if (tracks.isEmpty()) return
        playback.playTracks(tracks, index, queueTitle = "Continue listening")
    }

    fun shuffleFavorites() {
        viewModelScope.launch {
            val tracks = library.starredTracks().first()
            if (tracks.isNotEmpty()) playback.playTracks(tracks, 0, shuffle = true, queueTitle = "Favourites")
        }
    }
}
