package app.polyphony.ui.browse.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.CatalogTrack
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.ui.browse.catalog.CatalogArtistPageLogic
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

data class ExploreUiState(
    /** Songs from artists like yours (continuo); empty off continuo. Null while it's asked. */
    val forYou: ImmutableList<CatalogTrack>? = null,
    /** Songs with a request in flight from here. */
    val requesting: ImmutableSet<String> = persistentSetOf(),
    /** Albums of the library picked at random; Reshuffle picks again. */
    val randomPicks: ImmutableList<Album> = persistentListOf(),
)

/**
 * The Library's Explore tab: For you (songs you don't have from artists like yours, to play now
 * or request) and Random picks (albums of yours you might not have played in a while).
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

    private val forYou = combine(servers.activeServer.map { it?.id }.distinctUntilChanged(), reloads) { id, _ -> id }
        .mapLatest {
            val server = servers.getActive()?.takeIf { requests.supports(it) } ?: return@mapLatest emptyList()
            try {
                requests.discover(server)?.forYou.orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }

    val state: StateFlow<ExploreUiState> = combine(forYou, requester.requested, requester.requesting, randomPicks) { songs, requested, busy, picks ->
        ExploreUiState(
            forYou = CatalogArtistPageLogic.topTracks(songs, requested).toImmutableList(),
            requesting = busy.toImmutableSet(),
            randomPicks = picks.toImmutableList(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExploreUiState())

    fun requestTrack(track: CatalogTrack) = requester.requestTrack(track)

    fun reload() {
        reloads.value++
    }

    fun reshuffle() {
        viewModelScope.launch {
            settings.update { it.copy(library = it.library.copy(randomAlbumSeed = Random.nextLong())) }
        }
    }

    private companion object {
        const val RANDOM_PICKS = 24
    }
}
