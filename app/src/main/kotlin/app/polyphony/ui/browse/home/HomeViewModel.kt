package app.polyphony.ui.browse.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.CatalogAlbum
import app.polyphony.core.domain.model.Feedback
import app.polyphony.core.domain.model.PlayContext
import app.polyphony.core.domain.model.Playlist
import app.polyphony.core.domain.model.RecentContext
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.playback.PlaybackController
import app.polyphony.core.domain.repository.DownloadRepository
import app.polyphony.core.domain.repository.FeedbackRepository
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.LibrarySyncManager
import app.polyphony.core.domain.repository.ListeningHistoryRepository
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.core.domain.repository.SyncState
import app.polyphony.ui.browse.catalog.CatalogRequester
import app.polyphony.ui.common.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val isLoaded: Boolean = false,
    val servers: ImmutableList<Server> = persistentListOf(),
    val activeServer: Server? = null,
    /** Recently played collections, most recent first; each resumes where it was left. */
    val continueListening: ImmutableList<RecentContext> = persistentListOf(),
    val recentlyAdded: ImmutableList<Album> = persistentListOf(),
    val mostPlayed: ImmutableList<Album> = persistentListOf(),
    val favorites: ImmutableList<Album> = persistentListOf(),
    val playlists: ImmutableList<Playlist> = persistentListOf(),
    val sync: SyncState = SyncState(),
    val isRefreshing: Boolean = false,
    val downloadedCount: Int = 0,
    val downloadedBytes: Long = 0L,
) {
    val isLibraryEmpty: Boolean
        get() = isLoaded && recentlyAdded.isEmpty() && playlists.isEmpty() && continueListening.isEmpty()
}

/** Home's discovery row (continuo-linked servers); empty elsewhere, or when it can't say. */
data class DiscoveryUi(
    val newReleases: ImmutableList<CatalogAlbum> = persistentListOf(),
    /** Release groups with a request in flight from Home. */
    val requesting: ImmutableSet<String> = persistentSetOf(),
)

private data class Shelves(
    val continueListening: List<RecentContext>,
    val recentlyAdded: List<Album>,
    val mostPlayed: List<Album>,
    val favorites: List<Album>,
)

private data class Meta(
    val playlists: List<Playlist>,
    val servers: List<Server>,
    val activeServer: Server?,
    val sync: SyncState,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val syncManager: LibrarySyncManager,
    private val servers: ServerRepository,
    private val playback: PlaybackController,
    private val history: ListeningHistoryRepository,
    private val requests: RequestRepository,
    feedbackRepository: FeedbackRepository,
    downloads: DownloadRepository,
) : ViewModel() {

    /** The liked shelf reads "Top rated" in Ratings mode. */
    val feedback: StateFlow<Feedback> = feedbackRepository.feedback

    private val refreshing = MutableStateFlow(false)
    private val downloadSummary = combine(downloads.downloadedTrackIds(), downloads.storageUsage) { ids, usage -> ids.size to usage.usedBytes }
        .distinctUntilChanged()

    private val shelves = combine(
        history.recent(20),
        library.recentlyAddedAlbums(20),
        library.mostPlayedAlbums(20),
        library.likedAlbums(),
    ) { recent, added, played, starred -> Shelves(recent, added, played, starred.take(20)) }

    private val meta = combine(
        library.playlists(),
        servers.servers,
        servers.activeServer,
        syncManager.state,
    ) { playlists, serverList, active, sync -> Meta(playlists, serverList, active, sync) }

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
            favorites = s.favorites.toImmutableList(),
            playlists = m.playlists.toImmutableList(),
            sync = m.sync,
            isRefreshing = r,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    /** Request outcomes, for the snackbar. */
    val events: Flow<UiMessage> = messages.receiveAsFlow()
    private val requester = CatalogRequester(viewModelScope, servers, requests, messages)
    private val discoveryReloads = MutableStateFlow(0)

    /**
     * New from your artists, from continuo (cached there for hours): fetched when the
     * active server changes and on pull to refresh. A request made here shows on its row at once.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val discovery: StateFlow<DiscoveryUi> = combine(
        combine(servers.activeServer.map { it?.id }.distinctUntilChanged(), discoveryReloads) { id, _ -> id }
            .mapLatest {
                val server = servers.getActive()?.takeIf { requests.supports(it) } ?: return@mapLatest null
                try {
                    requests.discover(server)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            },
        requester.requested,
        requester.requesting,
    ) { found, requested, busy ->
        DiscoveryUi(
            newReleases = found?.newReleases.orEmpty()
                .map { a -> a.releaseGroupId?.let(requested::get)?.let { a.copy(availability = it) } ?: a }
                .toImmutableList(),
            requesting = busy.toImmutableSet(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiscoveryUi())

    fun request(releaseGroupId: String) = requester.request(releaseGroupId)

    fun refresh(full: Boolean = false) {
        discoveryReloads.value++
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

    fun switchServer(serverId: String) {
        viewModelScope.launch { servers.setActive(serverId) }
    }

    fun resume(entry: RecentContext) = playback.resumeContext(entry.context)

    fun playFromStart(entry: RecentContext) = playback.resumeContext(entry.context, fromStart = true)

    fun removeFromHistory(entry: RecentContext) {
        viewModelScope.launch { runCatching { history.remove(entry.context) } }
    }

    fun shuffleFavorites() {
        viewModelScope.launch {
            val tracks = library.likedTracks().first()
            val context = tracks.firstOrNull()?.let { PlayContext.favorites(it.serverId, artworkUrl = tracks.firstNotNullOfOrNull { t -> t.artworkUrl }) }
            if (tracks.isNotEmpty()) playback.playTracks(tracks, 0, shuffle = true, queueTitle = if (feedback.value.usesRatings) "Top rated" else "Favourites", context = context)
        }
    }
}
