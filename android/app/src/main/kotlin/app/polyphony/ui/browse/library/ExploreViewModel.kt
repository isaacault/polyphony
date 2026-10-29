package app.polyphony.ui.browse.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.CatalogAlbum
import app.polyphony.core.domain.model.CatalogArtist
import app.polyphony.core.domain.model.Discovery
import app.polyphony.core.domain.model.DiscoveryPlaylist
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.core.domain.repository.SettingsRepository
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

/** One row of Explore's albums: one you don't have, or one of yours you may have forgotten. */
sealed interface ExploreAlbum {
    data class New(val album: CatalogAlbum) : ExploreAlbum
    data class Yours(val album: Album) : ExploreAlbum
}

data class ExploreUiState(
    /** Null while continuo is asked (or empty off continuo). */
    val loading: Boolean = true,
    val newReleases: ImmutableList<CatalogAlbum> = persistentListOf(),
    val artists: ImmutableList<CatalogArtist> = persistentListOf(),
    val albums: ImmutableList<ExploreAlbum> = persistentListOf(),
    val playlists: ImmutableList<DiscoveryPlaylist> = persistentListOf(),
    /** Release groups with a request in flight from here. */
    val requesting: ImmutableSet<String> = persistentSetOf(),
    /** ListenBrainz isn't linked for scrobbling, so it makes no playlists for you: say how. */
    val playlistsNeedListenBrainz: Boolean = false,
)

/**
 * The Library's Explore tab, artists, albums and playlists rather than songs: new releases from
 * your artists, artists like yours, albums (theirs, mixed with forgotten ones of yours) and
 * ListenBrainz's playlists for you (continuo's discovery).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ExploreViewModel @Inject constructor(
    library: LibraryRepository,
    private val settings: SettingsRepository,
    private val servers: ServerRepository,
    private val requests: RequestRepository,
) : ViewModel() {

    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    /** Request outcomes, for the snackbar. */
    val events: Flow<UiMessage> = messages.receiveAsFlow()
    private val requester = CatalogRequester(viewModelScope, servers, requests, messages)
    private val reloads = MutableStateFlow(0)

    private val randomPicks = settings.settings
        .map { it.library.randomAlbumSeed }
        .distinctUntilChanged()
        .flatMapLatest { seed -> library.randomAlbums(RANDOM_PICKS, seed) }

    private val discovery = combine(servers.activeServer.map { it?.id }.distinctUntilChanged(), reloads) { id, _ -> id }
        .mapLatest {
            val server = servers.getActive()?.takeIf { requests.supports(it) } ?: return@mapLatest Discovery() to false
            try {
                val found = requests.discover(server) ?: Discovery()
                // A continuo that scrobbles, without ListenBrainz linked: its playlists need it.
                val needsLink = found.playlists.isEmpty() &&
                    runCatching { requests.scrobbling(server) }.getOrNull()?.listenBrainz?.connected == false
                found to needsLink
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Discovery() to false
            }
        }

    val state: StateFlow<ExploreUiState> = combine(discovery, requester.requested, requester.requesting, randomPicks) { (d, needsLink), requested, busy, picks ->
        fun CatalogAlbum.withRequest() = releaseGroupId?.let(requested::get)?.let { copy(availability = it) } ?: this
        ExploreUiState(
            loading = false,
            newReleases = d.newReleases.map { it.withRequest() }.toImmutableList(),
            artists = d.artists.toImmutableList(),
            albums = mix(d.albums.map { it.withRequest() }, picks).toImmutableList(),
            playlists = d.playlists.toImmutableList(),
            requesting = busy.toImmutableSet(),
            playlistsNeedListenBrainz = needsLink,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExploreUiState())

    fun request(releaseGroupId: String) = requester.request(releaseGroupId)

    fun reload() {
        reloads.value++
    }

    /** New random albums of yours. */
    fun reshuffle() {
        viewModelScope.launch {
            settings.update { it.copy(library = it.library.copy(randomAlbumSeed = Random.nextLong())) }
        }
    }

    companion object {
        private const val RANDOM_PICKS = 10

        /** Albums you don't have and ones of yours, taking turns. */
        fun mix(new: List<CatalogAlbum>, yours: List<Album>): List<ExploreAlbum> {
            val out = ArrayList<ExploreAlbum>(new.size + yours.size)
            for (i in 0 until maxOf(new.size, yours.size)) {
                new.getOrNull(i)?.let { out += ExploreAlbum.New(it) }
                yours.getOrNull(i)?.let { out += ExploreAlbum.Yours(it) }
            }
            return out
        }
    }
}
