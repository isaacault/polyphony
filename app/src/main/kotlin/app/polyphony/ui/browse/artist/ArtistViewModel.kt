package app.polyphony.ui.browse.artist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Artist
import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.CatalogAlbum
import app.polyphony.core.domain.model.Feedback
import app.polyphony.core.domain.model.ItemKind
import app.polyphony.core.domain.model.PlayContext
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.playback.PlaybackController
import app.polyphony.core.domain.repository.FeedbackRepository
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.navigation.ArtistRoute
import app.polyphony.ui.browse.catalog.CatalogRequester
import app.polyphony.ui.browse.catalog.Discography
import app.polyphony.ui.browse.catalog.DiscographyGroup
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ArtistUiState(
    val artist: Artist? = null,
    val albums: ImmutableList<Album> = persistentListOf(),
    val topTracks: ImmutableList<Track> = persistentListOf(),
    val similarArtists: ImmutableList<Artist> = persistentListOf(),
    val currentTrackId: String? = null,
    val isPlaying: Boolean = false,
    val isLoaded: Boolean = false,
    val refreshFailed: Boolean = false,
    val topTracksLoading: Boolean = true,
    /** The rest of the discography on a continuo server ([Discography.missing]); empty elsewhere. */
    val catalogGroups: ImmutableList<DiscographyGroup> = persistentListOf(),
    val catalogLoading: Boolean = false,
    val catalogFailed: Boolean = false,
    /** Library albums the catalogue only partly matches, by album id. */
    val partials: ImmutableMap<String, Availability.Partial> = persistentMapOf(),
    val requesting: ImmutableSet<String> = persistentSetOf(),
)

/** The artist's catalogue discography: not asked for (no continuo server or no MBID), loading, or done. */
private sealed interface CatalogLoad {
    data object None : CatalogLoad
    data object Loading : CatalogLoad
    data object Failed : CatalogLoad
    data class Loaded(val albums: List<CatalogAlbum>) : CatalogLoad
}

@HiltViewModel
class ArtistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val library: LibraryRepository,
    feedbackRepository: FeedbackRepository,
    private val playback: PlaybackController,
    private val servers: ServerRepository,
    private val requests: RequestRepository,
) : ViewModel() {

    /** Heart or star in the header. */
    val feedback: StateFlow<Feedback> = feedbackRepository.feedback

    val artistId: String = savedStateHandle.toRoute<ArtistRoute>().artistId
    private val refreshFailed = MutableStateFlow(false)
    private val topTracks = MutableStateFlow<List<Track>?>(null)
    private val similar = MutableStateFlow<List<Artist>>(emptyList())
    private val catalog = MutableStateFlow<CatalogLoad>(CatalogLoad.None)

    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    val events: Flow<UiMessage> = messages.receiveAsFlow()
    private val requester = CatalogRequester(viewModelScope, servers, requests, messages)

    private val catalogPart = combine(catalog, requester.requested, requester.requesting) { load, req, busy -> Triple(load, req, busy) }

    val state: StateFlow<ArtistUiState> = combine(
        library.artist(artistId),
        library.albumsByArtist(artistId),
        playback.state,
        refreshFailed,
        combine(topTracks, similar, catalogPart) { t, s, c -> Triple(t, s, c) },
    ) { artist, albums, pb, failed, (top, sim, cat) ->
        val (load, req, busy) = cat
        val catalogAlbums = (load as? CatalogLoad.Loaded)?.albums.orEmpty()
        ArtistUiState(
            artist = artist,
            albums = albums.sortedWith(compareByDescending<Album> { it.year ?: 0 }.thenBy { it.name }).toImmutableList(),
            topTracks = (top ?: emptyList()).toImmutableList(),
            similarArtists = sim.toImmutableList(),
            currentTrackId = pb.currentTrack?.id,
            isPlaying = pb.isPlaying,
            isLoaded = true,
            refreshFailed = failed,
            topTracksLoading = top == null,
            catalogGroups = Discography.missing(catalogAlbums, req).toImmutableList(),
            catalogLoading = load == CatalogLoad.Loading,
            catalogFailed = load == CatalogLoad.Failed,
            partials = Discography.partials(catalogAlbums).toImmutableMap(),
            requesting = busy.toImmutableSet(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArtistUiState())

    /** Ids of every cached track by this artist, for the header download button. */
    val trackIds: StateFlow<List<String>> = library.tracksForArtist(artistId)
        .map { tracks -> tracks.map { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun allTracks(): List<Track> = library.tracksForArtist(artistId).first()

    init {
        refresh()
        viewModelScope.launch {
            val artist = library.artist(artistId).filterNotNull().first()
            launch { loadDiscography(artist) }
            topTracks.value = runCatching { library.getArtistTopTracks(artist, 10) }.getOrDefault(emptyList())
            similar.value = runCatching { library.getSimilarArtists(artistId) }.getOrDefault(emptyList())
        }
    }

    /** On a continuo server, the artist's whole discography, so the page can offer what's missing. */
    private suspend fun loadDiscography(artist: Artist) {
        val mbid = artist.musicBrainzId ?: return
        val server = servers.getActive()?.takeIf { requests.supports(it) } ?: return
        catalog.value = CatalogLoad.Loading
        catalog.value = try {
            CatalogLoad.Loaded(requests.artistAlbums(server, mbid))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CatalogLoad.Failed
        }
    }

    fun request(releaseGroupId: String) = requester.request(releaseGroupId)

    fun refresh() {
        viewModelScope.launch {
            refreshFailed.value = false
            runCatching { library.refreshArtist(artistId) }.onFailure { refreshFailed.value = true }
        }
    }

    fun playTopTrack(index: Int) {
        val tracks = state.value.topTracks
        if (tracks.isEmpty()) return
        playback.playTracks(tracks, index, queueTitle = state.value.artist?.name)
    }

    fun playAll(shuffle: Boolean) {
        viewModelScope.launch {
            val tracks = library.tracksForArtist(artistId).first()
            val artist = state.value.artist
            if (tracks.isNotEmpty()) playback.playTracks(tracks, 0, shuffle, artist?.name, artist?.let(PlayContext::artist))
        }
    }

    fun rate(stars: Int) {
        val artist = state.value.artist ?: return
        viewModelScope.launch { runCatching { library.setRating(artist.id, ItemKind.ARTIST, stars) } }
    }

    fun toggleFavorite() {
        val artist = state.value.artist ?: return
        viewModelScope.launch { runCatching { library.setStarred(artist.id, ItemKind.ARTIST, !artist.starred) } }
    }
}
