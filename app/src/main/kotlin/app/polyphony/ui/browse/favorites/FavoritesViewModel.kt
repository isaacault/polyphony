package app.polyphony.ui.browse.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Artist
import app.polyphony.core.domain.model.PlayContext
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.playback.PlaybackController
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import app.polyphony.core.domain.repository.LibraryLayout
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val playback: PlaybackController,
    settings: SettingsRepository,
) : ViewModel() {

    val tracks: StateFlow<ImmutableList<Track>?> = library.starredTracks().map { it.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val albums: StateFlow<ImmutableList<Album>?> = library.starredAlbums().map { it.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val artists: StateFlow<ImmutableList<Artist>?> = library.starredArtists().map { it.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val albumsGrid: StateFlow<Boolean> = settings.settings.map { it.appearance.albumsLayout == LibraryLayout.GRID }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val gridColumns: StateFlow<Int> = settings.settings.map { it.appearance.gridColumns }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val playbackState = playback.state

    init {
        viewModelScope.launch { runCatching { library.refreshStarred() } }
    }

    fun playTrack(index: Int) {
        val list = tracks.value ?: return
        playback.playTracks(list, index, queueTitle = "Favourites", context = favoritesContext(list))
    }

    fun playAll(shuffle: Boolean) {
        val list = tracks.value ?: return
        if (list.isNotEmpty()) playback.playTracks(list, 0, shuffle, "Favourites", favoritesContext(list))
    }

    private fun favoritesContext(tracks: List<Track>): PlayContext? =
        tracks.firstOrNull()?.let { PlayContext.favorites(it.serverId, artworkUrl = tracks.firstNotNullOfOrNull { t -> t.artworkUrl }) }
}
