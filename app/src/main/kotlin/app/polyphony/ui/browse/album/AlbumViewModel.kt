package app.polyphony.ui.browse.album

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Feedback
import app.polyphony.core.domain.model.ItemKind
import app.polyphony.core.domain.model.PlayContext
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.playback.PlaybackController
import app.polyphony.core.domain.repository.FeedbackRepository
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.navigation.AlbumRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlbumUiState(
    val album: Album? = null,
    val tracks: ImmutableList<Track> = persistentListOf(),
    val currentTrackId: String? = null,
    val isPlaying: Boolean = false,
    val showTrackNumbers: Boolean = true,
    val isLoaded: Boolean = false,
    val refreshFailed: Boolean = false,
    val refreshDone: Boolean = false,
) {
    val discCount: Int get() = tracks.mapNotNull { it.discNumber }.distinct().size
    val totalDurationMs: Long get() = tracks.sumOf { it.durationMs }
}

@HiltViewModel
class AlbumViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val library: LibraryRepository,
    feedbackRepository: FeedbackRepository,
    private val playback: PlaybackController,
    settings: SettingsRepository,
) : ViewModel() {

    /** Heart or star in the header. */
    val feedback: StateFlow<Feedback> = feedbackRepository.feedback

    val albumId: String = savedStateHandle.toRoute<AlbumRoute>().albumId
    private val refreshFailed = MutableStateFlow(false)
    private val refreshDone = MutableStateFlow(false)

    val state: StateFlow<AlbumUiState> = combine(
        library.album(albumId),
        library.tracksForAlbum(albumId),
        playback.state,
        settings.settings.map { it.appearance.showTrackNumbers },
        combine(refreshFailed, refreshDone) { f, d -> f to d },
    ) { album, tracks, pb, showNumbers, (failed, done) ->
        AlbumUiState(
            album = album,
            tracks = tracks.toImmutableList(),
            currentTrackId = pb.currentTrack?.id,
            isPlaying = pb.isPlaying,
            showTrackNumbers = showNumbers,
            isLoaded = true,
            refreshFailed = failed,
            refreshDone = done,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlbumUiState())

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            refreshFailed.value = false
            refreshDone.value = false
            runCatching { library.refreshAlbum(albumId) }.onFailure { refreshFailed.value = true }
            refreshDone.value = true
        }
    }

    fun play(startIndex: Int = 0, shuffle: Boolean = false) {
        val s = state.value
        if (s.tracks.isEmpty()) return
        playback.playTracks(s.tracks, startIndex, shuffle, s.album?.name, s.album?.let(PlayContext::album))
    }

    fun playNext() = state.value.tracks.takeIf { it.isNotEmpty() }?.let(playback::playNext)
    fun addToQueue() = state.value.tracks.takeIf { it.isNotEmpty() }?.let(playback::addToQueue)

    fun rate(stars: Int) {
        val album = state.value.album ?: return
        viewModelScope.launch { runCatching { library.setRating(album.id, ItemKind.ALBUM, stars) } }
    }

    fun toggleFavorite() {
        val album = state.value.album ?: return
        viewModelScope.launch { runCatching { library.setStarred(album.id, ItemKind.ALBUM, !album.starred) } }
    }
}
