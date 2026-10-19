package app.polyphony.ui.browse.artist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Artist
import app.polyphony.core.domain.model.ItemKind
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.playback.PlaybackController
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.navigation.ArtistRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
)

@HiltViewModel
class ArtistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val library: LibraryRepository,
    private val playback: PlaybackController,
) : ViewModel() {

    val artistId: String = savedStateHandle.toRoute<ArtistRoute>().artistId
    private val refreshFailed = MutableStateFlow(false)
    private val topTracks = MutableStateFlow<List<Track>?>(null)
    private val similar = MutableStateFlow<List<Artist>>(emptyList())

    val state: StateFlow<ArtistUiState> = combine(
        library.artist(artistId),
        library.albumsByArtist(artistId),
        playback.state,
        refreshFailed,
        combine(topTracks, similar) { t, s -> t to s },
    ) { artist, albums, pb, failed, (top, sim) ->
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
            topTracks.value = runCatching { library.getArtistTopTracks(artist, 10) }.getOrDefault(emptyList())
            similar.value = runCatching { library.getSimilarArtists(artistId) }.getOrDefault(emptyList())
        }
    }

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
            if (tracks.isNotEmpty()) playback.playTracks(tracks, 0, shuffle, state.value.artist?.name)
        }
    }

    fun toggleFavorite() {
        val artist = state.value.artist ?: return
        viewModelScope.launch { runCatching { library.setStarred(artist.id, ItemKind.ARTIST, !artist.starred) } }
    }
}
