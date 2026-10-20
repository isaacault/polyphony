package app.polyphony.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.ItemKind
import app.polyphony.core.domain.model.PlayContext
import app.polyphony.core.domain.model.Playlist
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.playback.PlaybackController
import app.polyphony.core.domain.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Shared actions behind the option sheets (track/album overflow, add-to-playlist). Any screen can
 * host the sheets without wiring its own plumbing.
 */
@HiltViewModel
class TrackActionsViewModel @Inject constructor(
    private val playback: PlaybackController,
    private val library: LibraryRepository,
) : ViewModel() {

    val playlists: StateFlow<ImmutableList<Playlist>> = library.playlists()
        .map { it.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    fun playNext(tracks: List<Track>) = playback.playNext(tracks)
    fun addToQueue(tracks: List<Track>) = playback.addToQueue(tracks)
    fun play(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false, title: String? = null, context: PlayContext? = null) =
        playback.playTracks(tracks, startIndex, shuffle, title, context)

    fun toggleStarred(track: Track) {
        viewModelScope.launch { runCatching { library.setStarred(track.id, ItemKind.TRACK, !track.starred) } }
    }

    fun toggleStarred(album: Album) {
        viewModelScope.launch { runCatching { library.setStarred(album.id, ItemKind.ALBUM, !album.starred) } }
    }

    fun addToPlaylist(playlistId: String, trackIds: List<String>, onDone: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch { onDone(runCatching { library.addToPlaylist(playlistId, trackIds) }) }
    }

    fun createPlaylist(name: String, trackIds: List<String>, onDone: (Result<Playlist>) -> Unit = {}) {
        viewModelScope.launch { onDone(runCatching { library.createPlaylist(name, trackIds) }) }
    }

    /** Tracks for an album, fetching from the server when the cache is still empty. */
    suspend fun tracksForAlbum(albumId: String): List<Track> {
        val cached = library.tracksForAlbum(albumId).first()
        if (cached.isNotEmpty()) return cached
        runCatching { library.refreshAlbum(albumId) }
        return library.tracksForAlbum(albumId).first()
    }

    fun withAlbumTracks(albumId: String, block: (List<Track>) -> Unit) {
        viewModelScope.launch { block(tracksForAlbum(albumId)) }
    }
}
