package app.polyphony.ui.settings.playback

import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.polyphony.R
import app.polyphony.core.domain.playback.PlaybackController
import app.polyphony.core.domain.repository.PlaybackSettings
import app.polyphony.core.domain.repository.ReplayGainMode
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.core.ui.util.formatBytes
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.settings.components.ActionPreference
import app.polyphony.ui.settings.components.ListOption
import app.polyphony.ui.settings.components.ListPreference
import app.polyphony.ui.settings.components.PreferenceNote
import app.polyphony.ui.settings.components.SegmentedPreference
import app.polyphony.ui.settings.components.SettingsScaffold
import app.polyphony.ui.settings.components.SliderPreference
import app.polyphony.ui.settings.components.SwitchPreference
import app.polyphony.ui.settings.components.preferenceCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

data class PlaybackSettingsUiState(
    val settings: PlaybackSettings,
    val audioSessionId: Int,
)

@HiltViewModel
class PlaybackSettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val playbackController: PlaybackController,
) : ViewModel() {

    val state: StateFlow<PlaybackSettingsUiState?> = combine(
        settingsRepository.settings,
        playbackController.state,
    ) { settings, playback ->
        PlaybackSettingsUiState(settings.playback, playback.audioSessionId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun update(transform: (PlaybackSettings) -> PlaybackSettings) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(playback = transform(it.playback)) }
        }
    }

    fun setSpeed(speed: Float) {
        update { it.copy(playbackSpeed = speed) }
        playbackController.setPlaybackSpeed(speed)
    }
}

@Composable
fun PlaybackSettingsScreen(viewModel: PlaybackSettingsViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val noEqualizerMessage = stringResource(R.string.settings_playback_no_equalizer)

    SettingsScaffold(
        title = stringResource(R.string.settings_playback),
        onBack = { navigator.back() },
        snackbarHostState = snackbar,
    ) {
        val ui = state ?: return@SettingsScaffold
        val s = ui.settings

        preferenceCategory(key = "audio", titleRes = R.string.settings_playback_audio) {
            SwitchPreference(
                title = stringResource(R.string.settings_playback_gapless),
                summary = stringResource(R.string.settings_playback_gapless_summary),
                checked = s.gapless,
                onCheckedChange = { on -> viewModel.update { it.copy(gapless = on) } },
            )
            SliderPreference(
                title = stringResource(R.string.settings_playback_crossfade),
                summary = stringResource(R.string.settings_playback_crossfade_summary),
                value = (s.crossfadeMs / 1000f).coerceIn(0f, 12f),
                valueRange = 0f..12f,
                steps = 11,
                valueLabel = { v ->
                    val sec = v.roundToInt()
                    if (sec == 0) resources.getString(R.string.settings_off) else resources.getString(R.string.settings_seconds, sec)
                },
                onValueChangeFinished = { v -> viewModel.update { it.copy(crossfadeMs = v.roundToInt() * 1000) } },
            )
            SwitchPreference(
                title = stringResource(R.string.settings_playback_skip_silence),
                summary = stringResource(R.string.settings_playback_skip_silence_summary),
                checked = s.skipSilence,
                onCheckedChange = { on -> viewModel.update { it.copy(skipSilence = on) } },
            )
            ListPreference(
                title = stringResource(R.string.settings_playback_speed),
                options = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).map { ListOption(it, formatSpeed(it)) },
                selected = s.playbackSpeed,
                onSelected = viewModel::setSpeed,
                summary = formatSpeed(s.playbackSpeed),
            )
            ActionPreference(
                title = stringResource(R.string.settings_playback_equalizer),
                summary = stringResource(R.string.settings_playback_equalizer_summary),
                icon = Icons.Rounded.GraphicEq,
                onClick = {
                    val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
                        putExtra(AudioEffect.EXTRA_AUDIO_SESSION, ui.audioSessionId)
                        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                        putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                    }
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        scope.launch { snackbar.showSnackbar(noEqualizerMessage) }
                    }
                },
            )
        }

        preferenceCategory(key = "replaygain", titleRes = R.string.settings_playback_replaygain) {
            ListPreference(
                title = stringResource(R.string.settings_playback_replaygain_mode),
                options = listOf(
                    ListOption(ReplayGainMode.OFF, stringResource(R.string.settings_off)),
                    ListOption(ReplayGainMode.TRACK, stringResource(R.string.settings_playback_rg_track), stringResource(R.string.settings_playback_rg_track_summary)),
                    ListOption(ReplayGainMode.ALBUM, stringResource(R.string.settings_playback_rg_album), stringResource(R.string.settings_playback_rg_album_summary)),
                    ListOption(ReplayGainMode.AUTO, stringResource(R.string.settings_playback_rg_auto), stringResource(R.string.settings_playback_rg_auto_summary)),
                ),
                selected = s.replayGainMode,
                onSelected = { mode -> viewModel.update { it.copy(replayGainMode = mode) } },
            )
            SliderPreference(
                title = stringResource(R.string.settings_playback_preamp),
                value = s.replayGainPreampDb.coerceIn(-15f, 15f),
                valueRange = -15f..15f,
                steps = 29,
                valueLabel = { v -> String.format(Locale.US, "%+d dB", v.roundToInt()) },
                onValueChangeFinished = { v -> viewModel.update { it.copy(replayGainPreampDb = v.roundToInt().toFloat()) } },
                enabled = s.replayGainMode != ReplayGainMode.OFF,
            )
            SwitchPreference(
                title = stringResource(R.string.settings_playback_prevent_clipping),
                summary = stringResource(R.string.settings_playback_prevent_clipping_summary),
                checked = s.replayGainPreventClipping,
                onCheckedChange = { on -> viewModel.update { it.copy(replayGainPreventClipping = on) } },
                enabled = s.replayGainMode != ReplayGainMode.OFF,
            )
        }

        preferenceCategory(key = "behaviour", titleRes = R.string.settings_playback_behaviour) {
            SwitchPreference(
                title = stringResource(R.string.settings_playback_pause_on_disconnect),
                summary = stringResource(R.string.settings_playback_pause_on_disconnect_summary),
                checked = s.pauseOnDisconnect,
                onCheckedChange = { on -> viewModel.update { it.copy(pauseOnDisconnect = on) } },
            )
            SwitchPreference(
                title = stringResource(R.string.settings_playback_resume_on_connect),
                summary = stringResource(R.string.settings_playback_resume_on_connect_summary),
                checked = s.resumeOnConnect,
                onCheckedChange = { on -> viewModel.update { it.copy(resumeOnConnect = on) } },
            )
            SwitchPreference(
                title = stringResource(R.string.settings_playback_duck),
                summary = stringResource(R.string.settings_playback_duck_summary),
                checked = s.duckOnTransientFocusLoss,
                onCheckedChange = { on -> viewModel.update { it.copy(duckOnTransientFocusLoss = on) } },
            )
            SwitchPreference(
                title = stringResource(R.string.settings_playback_remember_queue),
                summary = stringResource(R.string.settings_playback_remember_queue_summary),
                checked = s.rememberQueue,
                onCheckedChange = { on -> viewModel.update { it.copy(rememberQueue = on) } },
            )
            SwitchPreference(
                title = stringResource(R.string.settings_playback_haptics),
                checked = s.hapticFeedback,
                onCheckedChange = { on -> viewModel.update { it.copy(hapticFeedback = on) } },
            )
        }

        preferenceCategory(key = "scrobble", titleRes = R.string.settings_playback_scrobbling) {
            SwitchPreference(
                title = stringResource(R.string.settings_playback_scrobble),
                summary = stringResource(R.string.settings_playback_scrobble_summary),
                checked = s.scrobble,
                onCheckedChange = { on -> viewModel.update { it.copy(scrobble = on) } },
            )
            SegmentedPreference(
                title = stringResource(R.string.settings_playback_scrobble_threshold),
                summary = stringResource(R.string.settings_playback_scrobble_threshold_summary),
                options = listOf(0.25f, 0.5f, 0.75f, 0.9f).map { ListOption(it, "${(it * 100).roundToInt()}%") },
                selected = s.scrobbleThreshold,
                onSelected = { t -> viewModel.update { it.copy(scrobbleThreshold = t) } },
                enabled = s.scrobble,
            )
        }

        preferenceCategory(key = "streaming", titleRes = R.string.settings_playback_buffering) {
            ListPreference(
                title = stringResource(R.string.settings_playback_stream_cache),
                options = listOf(256, 512, 1024, 2048, 4096).map { ListOption(it, formatBytes(it * 1024L * 1024L)) },
                selected = s.streamCacheSizeMb,
                onSelected = { mb -> viewModel.update { it.copy(streamCacheSizeMb = mb) } },
                summary = formatBytes(s.streamCacheSizeMb * 1024L * 1024L),
            )
            SwitchPreference(
                title = stringResource(R.string.settings_playback_preload),
                summary = stringResource(R.string.settings_playback_preload_summary),
                checked = s.preloadNextTrack,
                onCheckedChange = { on -> viewModel.update { it.copy(preloadNextTrack = on) } },
            )
            SwitchPreference(
                title = stringResource(R.string.settings_playback_prefetch),
                summary = stringResource(R.string.settings_playback_prefetch_summary),
                checked = s.prefetchQueue,
                onCheckedChange = { on -> viewModel.update { it.copy(prefetchQueue = on) } },
            )
            SwitchPreference(
                title = stringResource(R.string.settings_playback_prefetch_mobile),
                summary = stringResource(R.string.settings_playback_prefetch_mobile_summary),
                checked = s.prefetchOnMobileData,
                enabled = s.prefetchQueue,
                onCheckedChange = { on -> viewModel.update { it.copy(prefetchOnMobileData = on) } },
            )
            PreferenceNote(text = stringResource(R.string.settings_playback_buffering_note))
        }
    }
}

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else String.format(Locale.US, "%.2f×", speed).replace(".00", "").replace("0×", "×")
