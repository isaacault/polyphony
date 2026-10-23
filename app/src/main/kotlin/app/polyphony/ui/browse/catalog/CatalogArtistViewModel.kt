package app.polyphony.ui.browse.catalog

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.CatalogAlbum
import app.polyphony.core.domain.model.CatalogArtist
import app.polyphony.core.domain.model.CatalogArtistPage
import app.polyphony.core.domain.model.CatalogTrack
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.navigation.CatalogArtistRoute
import app.polyphony.ui.common.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CatalogArtistUiState(
    val name: String = "",
    val disambiguation: String? = null,
    val artworkUrl: String? = null,
    /** True when [artworkUrl] is the artist's own picture (round), not an album cover. */
    val hasArtistPicture: Boolean = false,
    val biography: String? = null,
    val topTracks: ImmutableList<CatalogTrack> = persistentListOf(),
    /** Releases continuo matched to the library anyway (a different artist name there): open them. */
    val inLibrary: ImmutableList<CatalogAlbum> = persistentListOf(),
    val groups: ImmutableList<DiscographyGroup> = persistentListOf(),
    val similarArtists: ImmutableList<CatalogArtist> = persistentListOf(),
    /** Similar artists that are in the library, by catalogue key → library artist id. */
    val libraryArtistIds: ImmutableMap<String, String> = persistentMapOf(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    val requesting: ImmutableSet<String> = persistentSetOf(),
) {
    val albumCount: Int get() = inLibrary.size + groups.sumOf { it.albums.size }
    val isEmpty: Boolean get() = !loading && !failed && inLibrary.isEmpty() && groups.isEmpty() && topTracks.isEmpty()
}

/** The pure parts of the catalogue artist page. */
object CatalogArtistPageLogic {

    /** A request made on this page shows straight away: on the song, or on every song of a requested album. */
    fun topTracks(tracks: List<CatalogTrack>, requested: Map<String, Availability>): List<CatalogTrack> =
        tracks.map { t -> t.requestKeys.firstNotNullOfOrNull(requested::get)?.let { t.copy(availability = it) } ?: t }

    /** The hero picture: the artist's own, else the newest album cover. */
    fun heroArtwork(page: CatalogArtistPage?, albums: List<CatalogAlbum>): String? =
        page?.artist?.artworkUrl ?: albums.sortedByDescending { it.year ?: 0 }.firstNotNullOfOrNull { it.artworkUrl }

    fun sameName(a: String, b: String): Boolean = loose(a) == loose(b)

    private fun loose(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}

/**
 * Any artist's page from the catalogue (#144): picture, biography, top songs, discography and
 * similar artists, laid out like a library artist's, with everything requestable. A continuo from
 * before artist pages answers only the discography; the page then shows just that.
 */
@HiltViewModel
class CatalogArtistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val servers: ServerRepository,
    private val requests: RequestRepository,
    private val library: LibraryRepository,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<CatalogArtistRoute>()
    private val page = MutableStateFlow<CatalogArtistPage?>(null)
    private val albums = MutableStateFlow<List<CatalogAlbum>?>(null)
    private val libraryIds = MutableStateFlow<Map<String, String>>(emptyMap())
    private val failed = MutableStateFlow(false)

    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    val events: Flow<UiMessage> = messages.receiveAsFlow()
    private val requester = CatalogRequester(viewModelScope, servers, requests, messages)

    private val loaded = combine(page, albums, libraryIds) { p, a, ids -> Triple(p, a, ids) }

    val state: StateFlow<CatalogArtistUiState> = combine(
        loaded, failed, requester.requested, requester.requesting,
    ) { (p, list, ids), didFail, req, busy ->
        val all = list.orEmpty()
        CatalogArtistUiState(
            name = p?.artist?.name?.takeIf { it.isNotBlank() } ?: route.name,
            disambiguation = p?.disambiguation,
            artworkUrl = CatalogArtistPageLogic.heroArtwork(p, all),
            hasArtistPicture = p?.artist?.artworkUrl != null,
            biography = p?.biography,
            topTracks = CatalogArtistPageLogic.topTracks(p?.topTracks.orEmpty(), req).toImmutableList(),
            inLibrary = all.filter {
                it.availability is Availability.InLibrary || (it.availability as? Availability.Partial)?.requestable == false
            }.toImmutableList(),
            groups = Discography.missing(all, req).toImmutableList(),
            similarArtists = p?.similarArtists.orEmpty().toImmutableList(),
            libraryArtistIds = ids.toImmutableMap(),
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
            // The page is extra: without it (older continuo, or it failed) the discography still shows.
            val pageJob = async { runCatching { requests.catalogArtist(server, route.musicBrainzId) }.getOrNull() }
            try {
                albums.value = requests.artistAlbums(server, route.musicBrainzId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed.value = true
            }
            page.value = pageJob.await()
            libraryIds.value = resolveLibraryArtists(page.value?.similarArtists.orEmpty())
        }
    }

    /** Similar artists the library has, so tapping one opens its library page. */
    private suspend fun resolveLibraryArtists(similar: List<CatalogArtist>): Map<String, String> =
        similar.filter { it.availability is Availability.InLibrary }.mapNotNull { a ->
            runCatching { library.searchLocal(a.name, 5).first().artists }.getOrNull()
                ?.firstOrNull { CatalogArtistPageLogic.sameName(it.name, a.name) }
                ?.let { a.key to it.id }
        }.toMap()

    fun request(releaseGroupId: String) = requester.request(releaseGroupId)

    fun requestTrack(track: CatalogTrack) = requester.requestTrack(track)
}
