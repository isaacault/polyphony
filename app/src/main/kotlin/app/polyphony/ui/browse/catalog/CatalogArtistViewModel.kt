package app.polyphony.ui.browse.catalog

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.CatalogAlbum
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.navigation.CatalogArtistRoute
import app.polyphony.ui.common.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CatalogArtistUiState(
    val name: String = "",
    /** Releases continuo matched to the library anyway (a different artist name there): open them. */
    val inLibrary: ImmutableList<CatalogAlbum> = persistentListOf(),
    val groups: ImmutableList<DiscographyGroup> = persistentListOf(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    val requesting: ImmutableSet<String> = persistentSetOf(),
) {
    val isEmpty: Boolean get() = !loading && !failed && inLibrary.isEmpty() && groups.isEmpty()
}

/** An artist with nothing in the library (#144): their discography from the catalogue, with Request. */
@HiltViewModel
class CatalogArtistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val servers: ServerRepository,
    private val requests: RequestRepository,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<CatalogArtistRoute>()
    private val albums = MutableStateFlow<List<CatalogAlbum>?>(null)
    private val failed = MutableStateFlow(false)

    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    val events: Flow<UiMessage> = messages.receiveAsFlow()
    private val requester = CatalogRequester(viewModelScope, servers, requests, messages)

    val state: StateFlow<CatalogArtistUiState> = combine(
        albums, failed, requester.requested, requester.requesting,
    ) { list, didFail, req, busy ->
        val all = list.orEmpty()
        CatalogArtistUiState(
            name = route.name,
            inLibrary = all.filter { it.availability is Availability.InLibrary || it.availability is Availability.Partial }.toImmutableList(),
            groups = Discography.missing(all, req).toImmutableList(),
            loading = list == null && !didFail,
            failed = didFail,
            requesting = busy.toImmutableSet(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CatalogArtistUiState(name = route.name))

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            failed.value = false
            albums.value = null
            val server = servers.getActive()?.takeIf { requests.supports(it) }
            if (server == null) {
                failed.value = true
                return@launch
            }
            try {
                albums.value = requests.artistAlbums(server, route.musicBrainzId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed.value = true
            }
        }
    }

    fun request(releaseGroupId: String) = requester.request(releaseGroupId)
}
