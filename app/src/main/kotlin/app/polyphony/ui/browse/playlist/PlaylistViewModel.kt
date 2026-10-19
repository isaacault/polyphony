package app.polyphony.ui.browse.playlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.polyphony.core.domain.model.Playlist
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.playback.PlaybackController
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.navigation.PlaylistRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaylistUiState(
    val playlist: Playlist? = null,
    val tracks: ImmutableList<Track> = persistentListOf(),
    val currentTrackId: String? = null,
    val isPlaying: Boolean = false,
    val isLoaded: Boolean = false,
    val refreshFailed: Boolean = false,
    val deleted: Boolean = false,
) {
    val totalDurationMs: Long get() = tracks.sumOf { it.durationMs }
}

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val library: LibraryRepository,
    private val playback: PlaybackController,
) : ViewModel() {

    val playlistId: String = savedStateHandle.toRoute<PlaylistRoute>().playlistId
    private val refreshFailed = MutableStateFlow(false)
    private val deleted = MutableStateFlow(false)

    val state: StateFlow<PlaylistUiState> = combine(
        library.playlist(playlistId),
        library.tracksForPlaylist(playlistId),
        playback.state,
        refreshFailed,
        deleted,
    ) { playlist, tracks, pb, failed, isDeleted ->
        PlaylistUiState(
            playlist = playlist,
            tracks = tracks.toImmutableList(),
            currentTrackId = pb.currentTrack?.id,
            isPlaying = pb.isPlaying,
            isLoaded = true,
            refreshFailed = failed,
            deleted = isDeleted,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistUiState())

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            refreshFailed.value = false
            runCatching { library.refreshPlaylist(playlistId) }.onFailure { refreshFailed.value = true }
        }
    }

    fun play(startIndex: Int = 0, shuffle: Boolean = false) {
        val s = state.value
        if (s.tracks.isEmpty()) return
        playback.playTracks(s.tracks, startIndex, shuffle, s.playlist?.name)
    }

    fun playNext() = state.value.tracks.takeIf { it.isNotEmpty() }?.let(playback::playNext)
    fun addToQueue() = state.value.tracks.takeIf { it.isNotEmpty() }?.let(playback::addToQueue)

    fun removeAt(index: Int) {
        viewModelScope.launch { runCatching { library.removeFromPlaylist(playlistId, listOf(index)) } }
    }

    fun rename(name: String) {
        viewModelScope.launch { runCatching { library.renamePlaylist(playlistId, name) } }
    }

    fun delete() {
        viewModelScope.launch {
            runCatching { library.deletePlaylist(playlistId) }.onSuccess { deleted.value = true }
        }
    }
}
