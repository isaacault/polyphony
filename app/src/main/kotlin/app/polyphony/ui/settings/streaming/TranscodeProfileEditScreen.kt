package app.polyphony.ui.settings.streaming

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.polyphony.R
import app.polyphony.core.domain.playback.PlaybackController
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.core.domain.repository.TranscodeProfileRepository
import app.polyphony.core.domain.transcode.AudioCodec
import app.polyphony.core.domain.transcode.TranscodeProfile
import app.polyphony.navigation.LocalNavigator
import app.polyphony.navigation.TranscodeProfileEditRoute
import app.polyphony.ui.settings.components.ActionPreference
import app.polyphony.ui.settings.components.ConfirmDialog
import app.polyphony.ui.settings.components.InfoBanner
import app.polyphony.ui.settings.components.PreferenceCategory
import app.polyphony.ui.settings.components.PreferenceNote
import app.polyphony.ui.settings.components.SettingsScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ProfileEditUiState(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val profile: TranscodeProfile = newProfile(),
    val nameError: Boolean = false,
    val finished: Boolean = false,
) {
    val readOnly: Boolean get() = profile.builtIn
    val canDelete: Boolean get() = !isNew && !profile.builtIn
}

private fun newProfile() = TranscodeProfile(
    id = "user.${UUID.randomUUID()}",
    name = "",
    codec = AudioCodec.OPUS,
    maxBitrateKbps = 128,
)

@HiltViewModel
class TranscodeProfileEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val profileRepository: TranscodeProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val playbackController: PlaybackController,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<TranscodeProfileEditRoute>()
    private val _state = MutableStateFlow(ProfileEditUiState())
    val state: StateFlow<ProfileEditUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val existing = route.profileId?.let { profileRepository.getProfile(it) ?: TranscodeProfile.builtIn(it) }
            _state.update {
                if (existing == null) it.copy(loaded = true, isNew = true)
                else it.copy(loaded = true, isNew = false, profile = existing)
            }
        }
    }

    fun update(transform: (TranscodeProfile) -> TranscodeProfile) {
        _state.update { s -> if (s.readOnly) s else s.copy(profile = transform(s.profile), nameError = false) }
    }

    /** Turns a built-in profile into an editable copy. */
    fun duplicate() {
        _state.update { s ->
            s.copy(
                isNew = true,
                profile = s.profile.copy(
                    id = "user.${UUID.randomUUID()}",
                    name = "${s.profile.name} (copy)",
                    builtIn = false,
                ),
            )
        }
    }

    fun save() {
        val current = _state.value
        if (current.readOnly) return
        if (current.profile.name.isBlank()) {
            _state.update { it.copy(nameError = true) }
            return
        }
        viewModelScope.launch {
            profileRepository.upsertProfile(current.profile.copy(name = current.profile.name.trim()))
            playbackController.refreshTranscodeDecision()
            _state.update { it.copy(finished = true) }
        }
    }

    fun delete() {
        val current = _state.value
        if (!current.canDelete) return
        viewModelScope.launch {
            clearProfileReferences(current.profile.id, settingsRepository, profileRepository)
            profileRepository.deleteProfile(current.profile.id)
            playbackController.refreshTranscodeDecision()
            _state.update { it.copy(finished = true) }
        }
    }
}

private val BitrateOptions = listOf(0, 64, 96, 128, 160, 192, 256, 320, 512)
private val SampleRateOptions = listOf(0, 44_100, 48_000, 96_000)
private val BitDepthOptions = listOf(0, 16, 24)

@Composable
fun TranscodeProfileEditScreen(viewModel: TranscodeProfileEditViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state.finished) {
        if (state.finished) navigator.back()
    }

    SettingsScaffold(
        title = stringResource(
            when {
                state.readOnly -> R.string.settings_profile_view
                state.isNew -> R.string.settings_profile_new
                else -> R.string.settings_profile_edit
            },
        ),
        onBack = { navigator.back() },
        actions = {
            if (!state.readOnly) {
                TextButton(onClick = viewModel::save, enabled = state.loaded) {
                    Text(stringResource(R.string.settings_save))
                }
            }
        },
    ) {
        if (!state.loaded) return@SettingsScaffold
        val p = state.profile
        val readOnly = state.readOnly

        if (readOnly) {
            item(key = "builtin") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfoBanner(text = stringResource(R.string.settings_profile_builtin_note), icon = Icons.Rounded.Lock)
                    OutlinedButton(onClick = viewModel::duplicate, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_profile_duplicate))
                    }
                }
            }
        }

        item(key = "name") {
            PreferenceCategory(title = stringResource(R.string.settings_profile_section_name)) {
                OutlinedTextField(
                    value = p.name,
                    onValueChange = { name -> viewModel.update { it.copy(name = name) } },
                    label = { Text(stringResource(R.string.settings_profile_name)) },
                    singleLine = true,
                    enabled = !readOnly,
                    isError = state.nameError,
                    supportingText = if (state.nameError) {
                        { Text(stringResource(R.string.settings_profile_name_required)) }
                    } else {
                        null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }

        item(key = "format") {
            PreferenceCategory(title = stringResource(R.string.settings_profile_section_format)) {
                ChipGroup(
                    title = stringResource(R.string.settings_profile_codec),
                    enabled = !readOnly,
                ) {
                    AudioCodec.entries.forEach { codec ->
                        FilterChip(
                            selected = p.codec == codec,
                            enabled = !readOnly,
                            onClick = {
                                viewModel.update {
                                    val lossless = codec == AudioCodec.ORIGINAL || codec == AudioCodec.FLAC
                                    it.copy(codec = codec, maxBitrateKbps = if (lossless) 0 else it.maxBitrateKbps)
                                }
                            },
                            label = { Text(codec.label) },
                            leadingIcon = if (p.codec == codec) {
                                { Icon(Icons.Rounded.Check, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) }
                            } else {
                                null
                            },
                        )
                    }
                }
                val lossless = p.codec == AudioCodec.ORIGINAL || p.codec == AudioCodec.FLAC
                if (!lossless) {
                    ChipGroup(title = stringResource(R.string.settings_profile_bitrate), enabled = !readOnly) {
                        BitrateOptions.forEach { kbps ->
                            FilterChip(
                                selected = p.maxBitrateKbps == kbps,
                                enabled = !readOnly,
                                onClick = { viewModel.update { it.copy(maxBitrateKbps = kbps) } },
                                label = { Text(if (kbps == 0) stringResource(R.string.settings_unlimited) else "$kbps kbps") },
                            )
                        }
                    }
                } else {
                    PreferenceNote(text = stringResource(R.string.settings_profile_bitrate_lossless_note))
                }
                ChipGroup(title = stringResource(R.string.settings_profile_sample_rate), enabled = !readOnly) {
                    SampleRateOptions.forEach { hz ->
                        FilterChip(
                            selected = p.maxSampleRateHz == hz,
                            enabled = !readOnly,
                            onClick = { viewModel.update { it.copy(maxSampleRateHz = hz) } },
                            label = { Text(if (hz == 0) stringResource(R.string.settings_any) else formatKhz(hz)) },
                        )
                    }
                }
                ChipGroup(title = stringResource(R.string.settings_profile_bit_depth), enabled = !readOnly) {
                    BitDepthOptions.forEach { bits ->
                        FilterChip(
                            selected = p.maxBitDepth == bits,
                            enabled = !readOnly,
                            onClick = { viewModel.update { it.copy(maxBitDepth = bits) } },
                            label = { Text(if (bits == 0) stringResource(R.string.settings_any) else "$bits-bit") },
                        )
                    }
                }
                PreferenceNote(text = stringResource(R.string.settings_profile_server_note))
            }
        }

        item(key = "summary") {
            PreferenceCategory(title = stringResource(R.string.settings_profile_section_summary)) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = p.name.ifBlank { stringResource(R.string.settings_profile_unnamed) },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = p.summary,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            if (p.isPassthrough) R.string.settings_profile_passthrough_hint else R.string.settings_profile_transcode_hint,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (!readOnly) {
            item(key = "save") {
                Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_save))
                }
            }
        }
        if (state.canDelete) {
            item(key = "delete") {
                PreferenceCategory(title = null) {
                    ActionPreference(
                        title = stringResource(R.string.settings_profile_delete),
                        summary = stringResource(R.string.settings_profile_delete_summary),
                        icon = Icons.Rounded.Delete,
                        destructive = true,
                        showChevron = false,
                        onClick = { confirmDelete = true },
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.settings_profile_delete_title, state.profile.name),
            text = stringResource(R.string.settings_profile_delete_text),
            confirmLabel = stringResource(R.string.settings_delete),
            onConfirm = {
                confirmDelete = false
                viewModel.delete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun ChipGroup(
    title: String,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .alpha(if (enabled) 1f else 0.6f),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            content()
        }
    }
}

private fun formatKhz(hz: Int): String {
    val khz = hz / 1000.0
    return if (khz == khz.toInt().toDouble()) "${khz.toInt()} kHz" else "$khz kHz"
}
