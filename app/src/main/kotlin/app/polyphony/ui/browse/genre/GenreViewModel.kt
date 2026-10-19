package app.polyphony.ui.browse.genre

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.playback.PlaybackController
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import app.polyphony.core.domain.repository.LibraryLayout
import app.polyphony.navigation.GenreRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GenreViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val library: LibraryRepository,
    private val playback: PlaybackController,
    settings: SettingsRepository,
) : ViewModel() {

    val genre: String = savedStateHandle.toRoute<GenreRoute>().genre

    val albums: StateFlow<ImmutableList<Album>?> = library.albumsByGenre(genre)
        .map { it.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val tracks: StateFlow<ImmutableList<Track>?> = library.tracksByGenre(genre)
        .map { it.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val albumsGrid: StateFlow<Boolean> = settings.settings.map { it.appearance.albumsLayout == LibraryLayout.GRID }

        .distinctUntilChanged()

        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val gridColumns: StateFlow<Int> = settings.settings.map { it.appearance.gridColumns }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val playbackState = playback.state

    fun playTrack(index: Int) {
        val list = tracks.value ?: return
        playback.playTracks(list, index, queueTitle = genre)
    }

    fun shuffle() {
        viewModelScope.launch {
            val remote = runCatching { library.getRandomTracks(200, genre) }.getOrDefault(emptyList())
            val picks = if (remote.isNotEmpty()) remote else (tracks.value ?: library.tracksByGenre(genre).first()).shuffled().take(300)
            if (picks.isNotEmpty()) playback.playTracks(picks, 0, shuffle = false, queueTitle = genre)
        }
    }
}
