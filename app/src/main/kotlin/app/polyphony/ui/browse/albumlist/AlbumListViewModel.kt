package app.polyphony.ui.browse.albumlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.navigation.AlbumListKind
import app.polyphony.navigation.AlbumListRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

@HiltViewModel
class AlbumListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    library: LibraryRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    val kind: AlbumListKind = runCatching { AlbumListKind.valueOf(savedStateHandle.toRoute<AlbumListRoute>().kind) }
        .getOrDefault(AlbumListKind.RECENTLY_ADDED)

    val albums: StateFlow<ImmutableList<Album>?> = when (kind) {
        AlbumListKind.RECENTLY_ADDED -> library.recentlyAddedAlbums(200)
        AlbumListKind.RECENTLY_PLAYED -> library.recentlyPlayedAlbums(200)
        AlbumListKind.MOST_PLAYED -> library.mostPlayedAlbums(200)
        AlbumListKind.STARRED -> library.starredAlbums()
        AlbumListKind.RANDOM -> settings.settings.map { it.library.randomAlbumSeed }.distinctUntilChanged()
            .flatMapLatest { seed -> library.randomAlbums(100, seed) }
    }.map { it.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val gridColumns: StateFlow<Int> = settings.settings.map { it.appearance.gridColumns }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun reshuffle() {
        viewModelScope.launch {
            settings.update { it.copy(library = it.library.copy(randomAlbumSeed = Random.nextLong())) }
        }
    }
}
