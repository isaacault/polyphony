package app.polyphony.ui.settings.playback

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.polyphony.R
import app.polyphony.core.domain.playback.EqualizerBands
import app.polyphony.core.domain.playback.EqualizerMode
import app.polyphony.core.domain.playback.EqualizerPreset
import app.polyphony.core.domain.playback.EqualizerSettings
import app.polyphony.core.domain.playback.PlaybackController
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.settings.components.ActionPreference
import app.polyphony.ui.settings.components.ListOption
import app.polyphony.ui.settings.components.PreferenceCategory
import app.polyphony.ui.settings.components.PreferenceNote
import app.polyphony.ui.settings.components.SegmentedPreference
import app.polyphony.ui.settings.components.SettingsScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.floor

data class EqualizerUiState(
    val mode: EqualizerMode,
    val preset: EqualizerPreset,
    val gainsDb: ImmutableList<Float>,
    val audioSessionId: Int,
)

@HiltViewModel
class EqualizerSettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    playbackController: PlaybackController,
) : ViewModel() {

    val state: StateFlow<EqualizerUiState?> = combine(
        settingsRepository.settings.map { it.playback.equalizer }.distinctUntilChanged(),
        playbackController.state.map { it.audioSessionId }.distinctUntilChanged(),
    ) { eq, session ->
        EqualizerUiState(eq.mode, eq.preset, eq.gainsDb.toImmutableList(), session)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun update(transform: (EqualizerSettings) -> EqualizerSettings) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(playback = it.playback.copy(equalizer = transform(it.playback.equalizer))) }
        }
    }

    fun setMode(mode: EqualizerMode) = update { it.copy(mode = mode) }
    fun setPreset(preset: EqualizerPreset) = update { it.copy(preset = preset) }
    fun setBand(band: Int, gainDb: Float) = update { it.withBand(band, gainDb) }
}

@Composable
fun EqualizerSettingsScreen(viewModel: EqualizerSettingsViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val noEqualizerMessage = stringResource(R.string.settings_playback_no_equalizer)

    SettingsScaffold(
        title = stringResource(R.string.settings_playback_equalizer),
        onBack = { navigator.back() },
        snackbarHostState = snackbar,
    ) {
        val ui = state ?: return@SettingsScaffold

        item(key = "mode", contentType = "category") {
            PreferenceCategory(title = null) {
                SegmentedPreference(
                    title = stringResource(R.string.settings_equalizer_mode),
                    summary = stringResource(
                        when (ui.mode) {
                            EqualizerMode.OFF -> R.string.settings_equalizer_mode_off_summary
                            EqualizerMode.BUILT_IN -> R.string.settings_equalizer_mode_built_in_summary
                            EqualizerMode.SYSTEM -> R.string.settings_equalizer_mode_system_summary
                        },
                    ),
                    options = listOf(
                        ListOption(EqualizerMode.OFF, stringResource(R.string.settings_equalizer_mode_off)),
                        ListOption(EqualizerMode.BUILT_IN, stringResource(R.string.settings_equalizer_mode_built_in)),
                        ListOption(EqualizerMode.SYSTEM, stringResource(R.string.settings_equalizer_mode_system)),
                    ),
                    selected = ui.mode,
                    onSelected = viewModel::setMode,
                )
                if (ui.mode == EqualizerMode.SYSTEM) {
                    ActionPreference(
                        title = stringResource(R.string.settings_equalizer_open_system),
                        summary = stringResource(R.string.settings_equalizer_open_system_summary),
                        icon = Icons.Rounded.GraphicEq,
                        onClick = {
                            if (!openSystemEqualizer(context, ui.audioSessionId)) scope.launch { snackbar.showSnackbar(noEqualizerMessage) }
                        },
                    )
                }
                if (ui.mode != EqualizerMode.OFF) PreferenceNote(text = stringResource(R.string.settings_equalizer_local_only))
            }
        }

        if (ui.mode == EqualizerMode.BUILT_IN) {
            item(key = "presets", contentType = "category") {
                PreferenceCategory(title = stringResource(R.string.settings_equalizer_presets), modifier = Modifier.animateItem()) {
                    FlowRow(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        EqualizerPreset.entries.forEach { preset ->
                            FilterChip(
                                selected = preset == ui.preset,
                                onClick = { viewModel.setPreset(preset) },
                                label = { Text(stringResource(preset.labelRes())) },
                            )
                        }
                    }
                }
            }
            item(key = "bands", contentType = "category") {
                PreferenceCategory(title = stringResource(R.string.settings_equalizer_bands), modifier = Modifier.animateItem()) {
                    EqualizerBandsEditor(gainsDb = ui.gainsDb, onBandChanged = viewModel::setBand)
                    PreferenceNote(text = stringResource(R.string.settings_equalizer_bands_note))
                }
            }
        }
    }
}

/** The phone's own effects panel for this session (or an equaliser app's); false when there is none. */
private fun openSystemEqualizer(context: Context, audioSessionId: Int): Boolean {
    val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
        putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
        putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

@StringRes
fun EqualizerPreset.labelRes(): Int = when (this) {
    EqualizerPreset.FLAT -> R.string.settings_equalizer_preset_flat
    EqualizerPreset.BASS_BOOST -> R.string.settings_equalizer_preset_bass_boost
    EqualizerPreset.BASS_CUT -> R.string.settings_equalizer_preset_bass_cut
    EqualizerPreset.TREBLE_BOOST -> R.string.settings_equalizer_preset_treble_boost
    EqualizerPreset.TREBLE_CUT -> R.string.settings_equalizer_preset_treble_cut
    EqualizerPreset.VOCAL -> R.string.settings_equalizer_preset_vocal
    EqualizerPreset.LOUDNESS -> R.string.settings_equalizer_preset_loudness
    EqualizerPreset.ROCK -> R.string.settings_equalizer_preset_rock
    EqualizerPreset.POP -> R.string.settings_equalizer_preset_pop
    EqualizerPreset.JAZZ -> R.string.settings_equalizer_preset_jazz
    EqualizerPreset.CLASSICAL -> R.string.settings_equalizer_preset_classical
    EqualizerPreset.ELECTRONIC -> R.string.settings_equalizer_preset_electronic
    EqualizerPreset.HIP_HOP -> R.string.settings_equalizer_preset_hip_hop
    EqualizerPreset.ACOUSTIC -> R.string.settings_equalizer_preset_acoustic
    EqualizerPreset.LATE_NIGHT -> R.string.settings_equalizer_preset_late_night
    EqualizerPreset.CUSTOM -> R.string.settings_equalizer_preset_custom
}

/** "+3", "−4.5", "0": a band's gain over its fader. */
internal fun formatGain(gainDb: Float): String {
    val text = if (gainDb == floor(gainDb)) gainDb.toInt().toString() else String.format(Locale.US, "%.1f", gainDb)
    return when {
        gainDb > 0f -> "+$text"
        gainDb < 0f -> text.replace("-", "−")
        else -> "0"
    }
}

/**
 * A fader per band, side by side as on a graphic equaliser. A band follows the finger while it is
 * dragged and is saved when let go; a tap sets it where it lands, a double-tap puts it back to 0 dB.
 * Each fader is a slider to TalkBack (swipe up or down to change it).
 */
@Composable
private fun EqualizerBandsEditor(gainsDb: ImmutableList<Float>, onBandChanged: (Int, Float) -> Unit) {
    // The band under the finger, until the saved curve catches up with it.
    var moving by remember(gainsDb) { mutableStateOf<Pair<Int, Float>?>(null) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 12.dp),
    ) {
        EqualizerBands.FREQUENCIES_HZ.forEachIndexed { band, frequency ->
            val saved = gainsDb.getOrElse(band) { 0f }
            BandFader(
                value = moving?.takeIf { it.first == band }?.second ?: saved,
                label = EqualizerBands.label(frequency),
                description = stringResource(R.string.settings_equalizer_band_description, EqualizerBands.label(frequency)),
                onValueChange = { moving = band to it },
                onValueChangeFinished = { final ->
                    moving = band to final
                    if (final != saved) onBandChanged(band, final)
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BandFader(
    value: Float,
    label: String,
    description: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val min = EqualizerBands.MIN_GAIN_DB
    val max = EqualizerBands.MAX_GAIN_DB
    val change by rememberUpdatedState(onValueChange)
    val finish by rememberUpdatedState(onValueChangeFinished)
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.surfaceContainerHighest
    val zeroTick = MaterialTheme.colorScheme.outline
    val valueText = stringResource(R.string.settings_equalizer_band_value, formatGain(value))

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = formatGain(value),
            style = MaterialTheme.typography.labelSmall,
            color = if (value != 0f) active else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(FADER_HEIGHT)
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    stateDescription = valueText
                    progressBarRangeInfo = ProgressBarRangeInfo(value, min..max, steps = ((max - min) / EqualizerBands.STEP_DB).toInt() - 1)
                    setProgress { target ->
                        finish(EqualizerBands.clamp(target))
                        true
                    }
                }
                .pointerInput(Unit) {
                    fun valueAt(y: Float) = EqualizerBands.clamp(max - (y / size.height) * (max - min))
                    var last: Float? = null
                    detectVerticalDragGestures(
                        onDragStart = { offset -> last = valueAt(offset.y).also(change) },
                        onDragEnd = { last?.let(finish); last = null },
                        onDragCancel = { last?.let(finish); last = null },
                    ) { pointer, _ ->
                        pointer.consume()
                        last = valueAt(pointer.position.y).also(change)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { finish(0f) },
                        onTap = { offset -> finish(EqualizerBands.clamp(max - (offset.y / size.height) * (max - min))) },
                    )
                }
                .drawBehind {
                    val trackWidth = 6.dp.toPx()
                    val x = size.width / 2
                    fun yOf(v: Float) = (max - v) / (max - min) * size.height
                    val corner = CornerRadius(trackWidth / 2)
                    drawRoundRect(inactive, Offset(x - trackWidth / 2, 0f), Size(trackWidth, size.height), corner)
                    val zeroY = yOf(0f)
                    val valueY = yOf(value)
                    drawRect(zeroTick, Offset(x - trackWidth, zeroY - 0.5.dp.toPx()), Size(trackWidth * 2, 1.dp.toPx()))
                    if (value != 0f) {
                        drawRoundRect(active, Offset(x - trackWidth / 2, minOf(zeroY, valueY)), Size(trackWidth, abs(zeroY - valueY)), corner)
                    }
                    drawCircle(active, radius = 8.dp.toPx(), center = Offset(x, valueY))
                },
        )
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

private val FADER_HEIGHT = 180.dp
