package app.polyphony.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.polyphony.core.domain.model.Feedback
import app.polyphony.core.domain.model.ItemKind
import app.polyphony.core.domain.model.Lyrics
import app.polyphony.core.domain.model.Playlist
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.playback.CastController
import app.polyphony.core.domain.playback.CastState
import app.polyphony.core.domain.playback.PlaybackController
import app.polyphony.core.domain.playback.PlaybackPosition
import app.polyphony.core.domain.playback.PlaybackState
import app.polyphony.core.domain.playback.RepeatMode
import app.polyphony.core.domain.provider.MediaProvider
import app.polyphony.core.domain.repository.AppearanceSettings
import app.polyphony.core.domain.repository.FeedbackRepository
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.ProviderRegistry
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.core.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface LyricsUiState {
    data object Hidden : LyricsUiState
    data object Loading : LyricsUiState
    data object None : LyricsUiState
    data class Loaded(val lyrics: Lyrics) : LyricsUiState
}

/** Shared by the mini player and the full Now Playing screen (activity scoped). */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playbackController: PlaybackController,
    private val castController: CastController,
    private val libraryRepository: LibraryRepository,
    private val settingsRepository: SettingsRepository,
    private val serverRepository: ServerRepository,
    private val providerRegistry: ProviderRegistry,
    feedbackRepository: FeedbackRepository,
) : ViewModel() {

    private fun <T> Flow<T>.asState(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val playback: StateFlow<PlaybackState> = playbackController.state
    val cast: StateFlow<CastState> = castController.state
    val position: StateFlow<PlaybackPosition> = playbackController.position.asState(PlaybackPosition())

    val appearance: StateFlow<AppearanceSettings> = settingsRepository.settings
        .map { it.appearance }.distinctUntilChanged().asState(AppearanceSettings())

    val hapticsEnabled: StateFlow<Boolean> = settingsRepository.settings
        .map { it.playback.hapticFeedback }.distinctUntilChanged().asState(true)

    private val currentTrack: Flow<Track?> = playback.map { it.currentTrack }.distinctUntilChangedBy { it?.id }

    /** Live copy of the current track from the library cache (favourite/rating stay fresh). */
    val currentTrackLive: StateFlow<Track?> = currentTrack
        .flatMapLatest { track -> if (track == null) flowOf(null) else libraryRepository.track(track.id) }
        .asState(null)

    val isFavorite: StateFlow<Boolean> = combine(currentTrackLive, playback) { live, state ->
        live?.starred ?: (state.currentTrack?.starred ?: false)
    }.distinctUntilChanged().asState(false)

    val rating: StateFlow<Int> = combine(currentTrackLive, playback) { live, state ->
        live?.rating ?: (state.currentTrack?.rating ?: 0)
    }.distinctUntilChanged().asState(0)

    val feedback: StateFlow<Feedback> = feedbackRepository.feedback

    val playlists: StateFlow<List<Playlist>> = libraryRepository.playlists().asState(emptyList())

    // ---- Providers for large artwork URLs ----
    private val _providers = MutableStateFlow<Map<String, MediaProvider>>(emptyMap())
    val providers: StateFlow<Map<String, MediaProvider>> = _providers

    init {
        viewModelScope.launch {
            playback.map { state -> state.queue.mapTo(HashSet()) { it.serverId } }
                .distinctUntilChanged()
                .collect { serverIds ->
                    val missing = serverIds - _providers.value.keys
                    if (missing.isEmpty()) return@collect
                    val resolved = missing.mapNotNull { id ->
                        runCatching { serverRepository.get(id)?.let { server -> id to providerRegistry.forServer(server) } }
                            .getOrNull()
                    }
                    if (resolved.isNotEmpty()) _providers.update { it + resolved }
                }
        }
    }

    // ---- Lyrics ----
    private val _showLyrics = MutableStateFlow(false)
    val showLyrics: StateFlow<Boolean> = _showLyrics
    private val lyricsCache = HashMap<String, Lyrics?>()

    val lyrics: StateFlow<LyricsUiState> = combine(_showLyrics, currentTrack) { show, track -> show to track }
        .flatMapLatest { (show, track) ->
            if (!show || track == null) {
                flowOf<LyricsUiState>(LyricsUiState.Hidden)
            } else {
                flow<LyricsUiState> {
                    if (lyricsCache.containsKey(track.id)) {
                        emit(lyricsCache[track.id].toUiState())
                    } else {
                        emit(LyricsUiState.Loading)
                        val result = try {
                            libraryRepository.getLyrics(track)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                        lyricsCache[track.id] = result
                        emit(result.toUiState())
                    }
                }
            }
        }
        .asState(LyricsUiState.Hidden)

    private fun Lyrics?.toUiState(): LyricsUiState =
        if (this == null || lines.isEmpty()) LyricsUiState.None else LyricsUiState.Loaded(this)

    fun toggleLyrics() = _showLyrics.update { !it }

    // ---- Messages for snackbars ----
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    // ---- Transport ----
    fun play() = playbackController.play()
    fun pause() = playbackController.pause()
    fun togglePlayPause() = playbackController.togglePlayPause()
    fun next() = playbackController.next()
    fun previous() = playbackController.previous()
    fun seekTo(positionMs: Long) = playbackController.seekTo(positionMs)
    fun skipToQueueItem(index: Int) = playbackController.skipToQueueItem(index)
    fun removeFromQueue(index: Int) = playbackController.removeFromQueue(index)
    fun insertIntoQueue(index: Int, track: Track) = playbackController.insertIntoQueue(index, listOf(track))
    fun moveQueueItem(from: Int, to: Int) = playbackController.moveQueueItem(from, to)
    fun clearQueue() = playbackController.clearQueue()
    fun toggleShuffle() = playbackController.setShuffle(!playback.value.shuffleEnabled)
    fun cycleRepeat() {
        val next = when (playback.value.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        playbackController.setRepeatMode(next)
    }
    fun setPlaybackSpeed(speed: Float) = playbackController.setPlaybackSpeed(speed)
    fun restoreQuality() = playbackController.restoreQuality()
    fun disconnectCast() = castController.disconnect()

    // ---- Sleep timer ----
    fun setSleepTimerMinutes(minutes: Int?, finishTrack: Boolean = false) {
        playbackController.setSleepTimer(minutes?.let { System.currentTimeMillis() + it * 60_000L }, finishTrack)
    }

    /**
     * A finish-the-track timer that is already due: it pauses at the next track change, so pausing
     * or a speed change cannot make it fire early the way a "time left in the track" estimate did.
     */
    fun setSleepTimerEndOfTrack() {
        playbackController.setSleepTimer(System.currentTimeMillis(), finishTrack = true)
    }

    // ---- Library actions ----
    fun rate(stars: Int) {
        val track = playback.value.currentTrack ?: return
        viewModelScope.launch {
            try {
                libraryRepository.setRating(track.id, ItemKind.TRACK, stars)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(e.message ?: "Couldn't save the rating")
            }
        }
    }

    fun toggleFavorite() {
        val track = playback.value.currentTrack ?: return
        val starred = isFavorite.value
        viewModelScope.launch {
            try {
                libraryRepository.setStarred(track.id, ItemKind.TRACK, !starred)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(e.message ?: "Couldn't update favourite")
            }
        }
    }

    fun addCurrentToPlaylist(playlist: Playlist, onDone: (String) -> Unit) {
        val track = playback.value.currentTrack ?: return
        viewModelScope.launch {
            try {
                libraryRepository.addToPlaylist(playlist.id, listOf(track.id))
                onDone(playlist.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(e.message ?: "Couldn't update playlist")
            }
        }
    }

    fun createPlaylistWithCurrent(name: String, onDone: (String) -> Unit) {
        val track = playback.value.currentTrack ?: return
        viewModelScope.launch {
            try {
                val playlist = libraryRepository.createPlaylist(name, listOf(track.id))
                onDone(playlist.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(e.message ?: "Couldn't create playlist")
            }
        }
    }

    fun saveQueueAsPlaylist(name: String, onDone: (String) -> Unit) {
        val ids = playback.value.queue.map { it.id }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                val playlist = libraryRepository.createPlaylist(name, ids)
                onDone(playlist.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(e.message ?: "Couldn't create playlist")
            }
        }
    }

    fun showMessage(message: String) {
        _messages.tryEmit(message)
    }
}
