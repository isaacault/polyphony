package app.polyphony.ui.browse.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Artist
import app.polyphony.core.domain.model.Playlist
import app.polyphony.core.domain.model.SearchResults
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.playback.PlaybackController
import app.polyphony.core.domain.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val artists: ImmutableList<Artist> = persistentListOf(),
    val albums: ImmutableList<Album> = persistentListOf(),
    val tracks: ImmutableList<Track> = persistentListOf(),
    val playlists: ImmutableList<Playlist> = persistentListOf(),
    val recentSearches: ImmutableList<String> = persistentListOf(),
    val isSearchingRemote: Boolean = false,
    val remoteSearched: Boolean = false,
    val remoteFailed: Boolean = false,
    val currentTrackId: String? = null,
    val isPlaying: Boolean = false,
) {
    val hasQuery: Boolean get() = query.isNotBlank()
    val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && tracks.isEmpty() && playlists.isEmpty()
}

private data class RemoteState(
    val forQuery: String = "",
    val results: SearchResults? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val playback: PlaybackController,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val recent = MutableStateFlow<List<String>>(emptyList())
    private val remote = MutableStateFlow(RemoteState())
    private var remoteJob: Job? = null

    private val localResults = query
        .map { it.trim() }
        .debounce(150)
        .distinctUntilChanged()
        .flatMapLatest { q -> if (q.isBlank()) flowOf(SearchResults()) else library.searchLocal(q, 30) }

    private val matchingPlaylists = combine(query.map { it.trim() }.debounce(150), library.playlists()) { q, playlists ->
        if (q.isBlank()) emptyList() else playlists.filter { it.name.contains(q, ignoreCase = true) }.take(10)
    }

    val state: StateFlow<SearchUiState> = combine(
        query, localResults, matchingPlaylists, remote, recent,
    ) { q, local, playlists, remoteState, recentList ->
        val remoteResults = remoteState.results?.takeIf { remoteState.forQuery == q.trim() }
        SearchUiState(
            query = q,
            artists = merge(local.artists, remoteResults?.artists) { it.id },
            albums = merge(local.albums, remoteResults?.albums) { it.id },
            tracks = merge(local.tracks, remoteResults?.tracks) { it.id },
            playlists = playlists.toImmutableList(),
            recentSearches = recentList.toImmutableList(),
            isSearchingRemote = remoteState.loading && remoteState.forQuery == q.trim(),
            remoteSearched = remoteResults != null,
            remoteFailed = remoteState.failed && remoteState.forQuery == q.trim(),
        )
    }.combine(playback.state) { s, pb -> s.copy(currentTrackId = pb.currentTrack?.id, isPlaying = pb.isPlaying) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun onQueryChange(value: String) {
        query.value = value
        if (value.isBlank()) remote.value = RemoteState()
    }

    fun clear() = onQueryChange("")

    /** Called on IME search / explicit submit: remember the term and also hit the server. */
    fun submit() {
        val q = query.value.trim()
        if (q.isBlank()) return
        rememberSearch(q)
        searchRemote()
    }

    fun searchRemote() {
        val q = query.value.trim()
        if (q.isBlank()) return
        remoteJob?.cancel()
        remote.value = RemoteState(forQuery = q, loading = true)
        remoteJob = viewModelScope.launch {
            val result = runCatching { library.searchRemote(q) }
            remote.value = RemoteState(
                forQuery = q,
                results = result.getOrNull(),
                loading = false,
                failed = result.isFailure,
            )
        }
    }

    fun rememberSearch(term: String) {
        val t = term.trim()
        if (t.isBlank()) return
        recent.value = (listOf(t) + recent.value.filterNot { it.equals(t, ignoreCase = true) }).take(10)
    }

    fun removeRecent(term: String) {
        recent.value = recent.value - term
    }

    fun playTrack(index: Int) {
        val tracks = state.value.tracks
        if (index !in tracks.indices) return
        rememberSearch(query.value)
        playback.playTracks(tracks, index, queueTitle = "Search: ${query.value.trim()}")
    }

    private fun <T> merge(local: List<T>, remote: List<T>?, id: (T) -> String): ImmutableList<T> {
        if (remote.isNullOrEmpty()) return local.toImmutableList()
        val seen = local.mapTo(HashSet()) { id(it) }
        return (local + remote.filter { seen.add(id(it)) }).toImmutableList()
    }
}
