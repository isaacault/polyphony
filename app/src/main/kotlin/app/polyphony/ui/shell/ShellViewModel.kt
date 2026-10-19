package app.polyphony.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.polyphony.core.domain.playback.CastController
import app.polyphony.core.domain.playback.CastState
import app.polyphony.core.domain.playback.PlaybackController
import app.polyphony.core.domain.playback.PlaybackState
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.core.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ShellViewModel @Inject constructor(
    playbackController: PlaybackController,
    castController: CastController,
    serverRepository: ServerRepository,
    settingsRepository: SettingsRepository,
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
}
