package app.polyphony.ui.browse.catalog

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.CatalogAlbumDetail
import app.polyphony.core.domain.model.CatalogAlbumTrack
import app.polyphony.core.domain.model.CatalogTrack
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.navigation.CatalogAlbumRoute
import app.polyphony.ui.common.PlayNow
import app.polyphony.ui.common.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
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

data class Disc(val number: Int, val tracks: ImmutableList<CatalogAlbumTrack>)

data class CatalogAlbumUiState(
    val releaseGroupId: String = "",
    val title: String = "",
    val artist: String? = null,
    val year: Int? = null,
    val albumType: String? = null,
    val artworkUrl: String? = null,
    val availability: Availability = Availability.Requestable,
    val discs: ImmutableList<Disc> = persistentListOf(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    val busy: Boolean = false,
) {
    /** Disc labels only when there's more than one. */
    val showDiscLabels: Boolean get() = discs.size > 1
}

/** The pure parts of a catalogue album page (#145). */
object CatalogAlbumPage {

    /** A request made on this page wins over what the server said when the page loaded. */
    fun availability(detail: CatalogAlbumDetail?, requested: Map<String, Availability>, releaseGroupId: String): Availability =
        requested[releaseGroupId] ?: detail?.album?.availability ?: Availability.Requestable

    fun discs(tracks: List<CatalogAlbumTrack>): List<Disc> =
        tracks.groupBy { it.discNumber }.toSortedMap().map { (n, list) -> Disc(n, list.sortedBy { it.position }.toImmutableList()) }
}

/** An album that isn't in the library (#145): artwork, tracklist and Request, from the catalogue. */
@HiltViewModel
class CatalogAlbumViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val servers: ServerRepository,
    private val requests: RequestRepository,
    private val playNow: PlayNow,
) : ViewModel() {

    /** Whether this server can play the album's songs before you have them (continuo#12). */
    val canPlayNow: StateFlow<Boolean> = playNow.available

    private val route = savedStateHandle.toRoute<CatalogAlbumRoute>()
    private val detail = MutableStateFlow<CatalogAlbumDetail?>(null)
    private val failed = MutableStateFlow(false)

    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    val events: Flow<UiMessage> = messages.receiveAsFlow()
    private val requester = CatalogRequester(viewModelScope, servers, requests, messages)

    val state: StateFlow<CatalogAlbumUiState> = combine(
        detail, failed, requester.requested, requester.requesting,
    ) { d, didFail, req, busy ->
        val album = d?.album
        CatalogAlbumUiState(
            releaseGroupId = route.releaseGroupId,
            title = album?.title ?: route.title,
            artist = album?.artist,
            year = album?.year,
            albumType = album?.albumType,
            artworkUrl = album?.artworkUrl,
            availability = CatalogAlbumPage.availability(d, req, route.releaseGroupId),
            discs = CatalogAlbumPage.discs(d?.tracks.orEmpty()).toImmutableList(),
            loading = d == null && !didFail,
            failed = didFail,
            busy = route.releaseGroupId in busy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CatalogAlbumUiState(releaseGroupId = route.releaseGroupId, title = route.title))

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            failed.value = false
            detail.value = null
            val server = servers.getActive()?.takeIf { requests.supports(it) }
            if (server == null) {
                failed.value = true
                return@launch
            }
            try {
                detail.value = requests.catalogAlbum(server, route.releaseGroupId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed.value = true
            }
        }
    }

    fun request() = requester.request(route.releaseGroupId)

    /** Play a song of this album now, from Soulseek, while it downloads. */
    fun playTrack(track: CatalogAlbumTrack) {
        val s = state.value
        playNow.play(
            CatalogTrack(
                key = "mb:rec:${track.recordingId}", title = track.title, artist = s.artist, durationMs = track.durationMs,
                albumTitle = s.title, albumReleaseGroupId = route.releaseGroupId, availability = Availability.Requestable,
                recordingId = track.recordingId,
            ),
        )
    }
}
