package app.polyphony.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.polyphony.core.domain.playback.CastController
import app.polyphony.core.domain.playback.CastState
import app.polyphony.core.domain.playback.PlaybackController
import app.polyphony.core.domain.playback.PlaybackState
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.navigation.DeepLink
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ShellViewModel @Inject constructor(
    private val playbackController: PlaybackController,
    castController: CastController,
    private val serverRepository: ServerRepository,
    settingsRepository: SettingsRepository,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    val playback: StateFlow<PlaybackState> = playbackController.state
    val cast: StateFlow<CastState> = castController.state

    /** null until the server list has loaded, so the shell never flashes onboarding at start. */
    val hasServers: StateFlow<Boolean?> = serverRepository.servers
        .map { it.isNotEmpty() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val onboardingComplete: StateFlow<Boolean> = settingsRepository.settings
        .map { it.onboardingComplete }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val hapticsEnabled: StateFlow<Boolean> = settingsRepository.settings
        .map { it.playback.hapticFeedback }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /**
     * Shuffle the whole library and start playback. Mirrors `LibraryViewModel.shuffleLibrary` so
     * the `polyphony://shuffle` launcher shortcut behaves exactly like "Shuffle all" in Library:
     * a server-side random pick when online, a cached random sample otherwise.
     */
    fun shuffleLibrary() {
        viewModelScope.launch {
            val remote = runCatching { libraryRepository.getRandomTracks(SHUFFLE_CAP) }.getOrDefault(emptyList())
            val picks = remote.ifEmpty { libraryRepository.randomCachedTracks(SHUFFLE_CAP) }
            if (picks.isNotEmpty()) {
                playbackController.playTracks(picks, 0, shuffle = false, queueTitle = "Shuffle all")
            }
        }
    }

    /**
     * Makes the server an album link points at the active one (switching if needed), so the album
     * screen loads it from there. False when no configured server matches.
     */
    suspend fun activateServerFor(link: DeepLink.Album): Boolean {
        val server = DeepLink.serverFor(link, serverRepository.servers.first()) ?: return false
        if (serverRepository.getActive()?.id != server.id) serverRepository.setActive(server.id)
        return true
    }

    private companion object {
        const val SHUFFLE_CAP = 300
    }
}
