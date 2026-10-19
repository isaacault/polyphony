package app.polyphony.ui.settings.streaming

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.polyphony.R
import app.polyphony.core.domain.playback.CastController
import app.polyphony.core.domain.playback.PlaybackController
import app.polyphony.core.domain.repository.NetworkMonitor
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.core.domain.repository.TranscodeProfileRepository
import app.polyphony.core.domain.transcode.NetworkState
import app.polyphony.core.domain.transcode.OutputTarget
import app.polyphony.core.domain.transcode.TranscodeContext
import app.polyphony.core.domain.transcode.TranscodeDecision
import app.polyphony.core.domain.transcode.TranscodePolicy
import app.polyphony.core.domain.transcode.TranscodeProfile
import app.polyphony.core.domain.transcode.TranscodeResolver
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.settings.components.ActionPreference
import app.polyphony.ui.settings.components.ConfirmDialog
import app.polyphony.ui.settings.components.ExpandablePreference
import app.polyphony.ui.settings.components.ListOption
import app.polyphony.ui.settings.components.ListPreference
import app.polyphony.ui.settings.components.PreferenceCategory
import app.polyphony.ui.settings.components.PreferenceNote
import app.polyphony.ui.settings.components.PreferenceRow
import app.polyphony.ui.settings.components.ProfilePickerDialog
import app.polyphony.ui.settings.components.SegmentedPreference
import app.polyphony.ui.settings.components.SettingsScaffold
import app.polyphony.ui.settings.components.SliderPreference
import app.polyphony.ui.settings.components.SwitchPreference
import app.polyphony.ui.settings.components.preferenceCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

data class StreamingUiState(
    val policy: TranscodePolicy,
    val profiles: ImmutableList<TranscodeProfile>,
    val network: NetworkState,
    val outputTarget: OutputTarget,
    val decision: TranscodeDecision,
    /** True when [decision] comes from the player; false when it is a preview computed here. */
    val isLive: Boolean,
    val latencyDegraded: Boolean,
    val deviceOverride: TranscodeProfile?,
)

@HiltViewModel
class StreamingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val profileRepository: TranscodeProfileRepository,
    private val playbackController: PlaybackController,
    castController: CastController,
    networkMonitor: NetworkMonitor,
    private val resolver: TranscodeResolver,
) : ViewModel() {

    private data class Inputs(
        val policy: TranscodePolicy,
        val profiles: List<TranscodeProfile>,
        val overrides: Map<String, String?>,
    )

    private val inputs = combine(
        settingsRepository.settings.map { it.transcode },
        profileRepository.profiles,
        profileRepository.remoteDevices,
    ) { policy, profiles, devices ->
        Inputs(policy, profiles, devices.associate { it.targetKey to it.profileId })
    }

    val uiState: StateFlow<StreamingUiState?> = combine(
        inputs,
        networkMonitor.state,
        playbackController.state,
        castController.state,
    ) { input, network, playback, cast ->
        val target = cast.target?.takeIf { cast.isConnected } ?: playback.outputTarget
        val overrideId = input.overrides[target.key]
        val live = playback.transcode
        val decision = live ?: resolver.resolve(
            input.policy,
            input.profiles,
            TranscodeContext(network, target, playback.latencyDegraded, overrideId),
        )
        StreamingUiState(
            policy = input.policy,
            profiles = input.profiles.toImmutableList(),
            network = network,
            outputTarget = target,
            decision = decision,
            isLive = live != null,
            latencyDegraded = playback.latencyDegraded,
            deviceOverride = overrideId?.let { id -> input.profiles.firstOrNull { it.id == id } },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun updatePolicy(transform: (TranscodePolicy) -> TranscodePolicy) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(transcode = transform(it.transcode)) }
            playbackController.refreshTranscodeDecision()
        }
    }

    fun restoreQuality() = playbackController.restoreQuality()

    fun deleteProfile(id: String) {
        viewModelScope.launch {
            clearProfileReferences(id, settingsRepository, profileRepository)
            profileRepository.deleteProfile(id)
            playbackController.refreshTranscodeDecision()
        }
    }
}

@Composable
fun StreamingSettingsScreen(viewModel: StreamingViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<TranscodeProfile?>(null) }

    SettingsScaffold(title = stringResource(R.string.settings_streaming), onBack = { navigator.back() }) {
        val s = state ?: return@SettingsScaffold
        val policy = s.policy
        val profiles = s.profiles

        item(key = "now") {
            NowCard(state = s, onRestore = viewModel::restoreQuality)
        }

        preferenceCategory(key = "rules", titleRes = R.string.settings_streaming_rules) {
            ProfilePreference(
                title = stringResource(R.string.settings_streaming_default_profile),
                profiles = profiles,
                selectedId = policy.defaultProfileId,
                noneLabel = null,
                onSelected = { id -> viewModel.updatePolicy { it.copy(defaultProfileId = id ?: TranscodeProfile.ORIGINAL_ID) } },
            )
            ProfilePreference(
                title = stringResource(R.string.settings_streaming_on_wifi),
                profiles = profiles,
                selectedId = policy.wifiProfileId,
                noneLabel = stringResource(R.string.settings_streaming_use_default),
                onSelected = { id -> viewModel.updatePolicy { it.copy(wifiProfileId = id) } },
            )
            ProfilePreference(
                title = stringResource(R.string.settings_streaming_on_cellular),
                profiles = profiles,
                selectedId = policy.cellularProfileId,
                noneLabel = stringResource(R.string.settings_streaming_use_default),
                onSelected = { id -> viewModel.updatePolicy { it.copy(cellularProfileId = id) } },
            )
            SwitchPreference(
                title = stringResource(R.string.settings_streaming_metered_wifi),
                summary = stringResource(R.string.settings_streaming_metered_wifi_summary),
                checked = policy.meteredWifiAsCellular,
                onCheckedChange = { on -> viewModel.updatePolicy { it.copy(meteredWifiAsCellular = on) } },
            )
            SwitchPreference(
                title = stringResource(R.string.settings_streaming_reload_current),
                summary = stringResource(R.string.settings_streaming_reload_current_summary),
                checked = policy.reloadCurrentTrackOnChange,
                onCheckedChange = { on -> viewModel.updatePolicy { it.copy(reloadCurrentTrackOnChange = on) } },
            )
        }

        preferenceCategory(key = "casting", titleRes = R.string.settings_streaming_casting) {
            ProfilePreference(
                title = stringResource(R.string.settings_streaming_cast_default),
                profiles = profiles,
                selectedId = policy.castDefaultProfileId,
                noneLabel = stringResource(R.string.settings_streaming_use_network_rules),
                onSelected = { id -> viewModel.updatePolicy { it.copy(castDefaultProfileId = id) } },
            )
            ActionPreference(
                title = stringResource(R.string.settings_streaming_per_device),
                summary = stringResource(R.string.settings_streaming_per_device_summary),
                onClick = navigator::openRemoteDevicesSettings,
            )
            if (s.outputTarget.isRemote && s.deviceOverride != null) {
                PreferenceNote(
                    text = stringResource(
                        R.string.settings_streaming_device_override_note,
                        s.outputTarget.displayName,
                        s.deviceOverride.name,
                    ),
                )
            }
        }

        preferenceCategory(key = "latency", titleRes = R.string.settings_streaming_adaptive) {
            val latency = policy.adaptiveLatency
            SwitchPreference(
                title = stringResource(R.string.settings_streaming_adaptive_enable),
                summary = stringResource(R.string.settings_streaming_adaptive_enable_summary),
                checked = latency.enabled,
                onCheckedChange = { on -> viewModel.updatePolicy { it.copy(adaptiveLatency = it.adaptiveLatency.copy(enabled = on)) } },
            )
            ProfilePreference(
                title = stringResource(R.string.settings_streaming_adaptive_cap),
                profiles = profiles,
                selectedId = latency.highLatencyProfileId,
                noneLabel = stringResource(R.string.settings_streaming_adaptive_cap_none),
                enabled = latency.enabled,
                onSelected = { id ->
                    viewModel.updatePolicy { it.copy(adaptiveLatency = it.adaptiveLatency.copy(highLatencyProfileId = id)) }
                },
            )
            SegmentedPreference(
                title = stringResource(R.string.settings_streaming_sensitivity),
                summary = stringResource(R.string.settings_streaming_sensitivity_summary),
                options = listOf(
                    ListOption(LatencySensitivity.LOW, stringResource(R.string.settings_low)),
                    ListOption(LatencySensitivity.MEDIUM, stringResource(R.string.settings_medium)),
                    ListOption(LatencySensitivity.HIGH, stringResource(R.string.settings_high)),
                ),
                selected = LatencySensitivity.matching(latency),
                enabled = latency.enabled,
                onSelected = { preset ->
                    viewModel.updatePolicy {
                        it.copy(
                            adaptiveLatency = it.adaptiveLatency.copy(
                                stallThreshold = preset.stallThreshold,
                                windowSeconds = preset.windowSeconds,
                                longStallMs = preset.longStallMs,
                            ),
                        )
                    }
                },
            )
            ListPreference(
                title = stringResource(R.string.settings_streaming_auto_recover),
                options = listOf(
                    ListOption(0, stringResource(R.string.settings_streaming_auto_recover_never)),
                    ListOption(300, stringResource(R.string.settings_minutes, 5)),
                    ListOption(600, stringResource(R.string.settings_minutes, 10)),
                    ListOption(1800, stringResource(R.string.settings_minutes, 30)),
                ),
                selected = latency.recoverAfterSeconds,
                enabled = latency.enabled,
                onSelected = { secs ->
                    viewModel.updatePolicy { it.copy(adaptiveLatency = it.adaptiveLatency.copy(recoverAfterSeconds = secs)) }
                },
            )
            SwitchPreference(
                title = stringResource(R.string.settings_streaming_adaptive_cast),
                summary = stringResource(R.string.settings_streaming_adaptive_cast_summary),
                checked = latency.applyToRemoteOutputs,
                enabled = latency.enabled,
                onCheckedChange = { on ->
                    viewModel.updatePolicy { it.copy(adaptiveLatency = it.adaptiveLatency.copy(applyToRemoteOutputs = on)) }
                },
            )
            ExpandablePreference(
                title = stringResource(R.string.settings_advanced),
                summary = stringResource(R.string.settings_streaming_advanced_summary),
            ) {
                SliderPreference(
                    title = stringResource(R.string.settings_streaming_stall_threshold),
                    value = latency.stallThreshold.toFloat(),
                    valueRange = 1f..10f,
                    steps = 8,
                    valueLabel = { it.roundToInt().toString() },
                    enabled = latency.enabled,
                    onValueChangeFinished = { v ->
                        viewModel.updatePolicy { it.copy(adaptiveLatency = it.adaptiveLatency.copy(stallThreshold = v.roundToInt())) }
                    },
                )
                SliderPreference(
                    title = stringResource(R.string.settings_streaming_window),
                    value = latency.windowSeconds.toFloat(),
                    valueRange = 30f..600f,
                    steps = 18,
                    valueLabel = { "${it.roundToInt()} s" },
                    enabled = latency.enabled,
                    onValueChangeFinished = { v ->
                        viewModel.updatePolicy { it.copy(adaptiveLatency = it.adaptiveLatency.copy(windowSeconds = v.roundToInt())) }
                    },
                )
                SliderPreference(
                    title = stringResource(R.string.settings_streaming_long_stall),
                    value = (latency.longStallMs / 1000f).coerceIn(1f, 10f),
                    valueRange = 1f..10f,
                    steps = 8,
                    valueLabel = { "${it.roundToInt()} s" },
                    enabled = latency.enabled,
                    onValueChangeFinished = { v ->
                        viewModel.updatePolicy { it.copy(adaptiveLatency = it.adaptiveLatency.copy(longStallMs = v.roundToInt() * 1000L)) }
                    },
                )
            }
        }

        preferenceCategory(key = "profiles", titleRes = R.string.settings_streaming_profiles) {
            profiles.forEachIndexed { index, profile ->
                ProfileRow(
                    profile = profile,
                    onEdit = { navigator.openTranscodeProfile(profile.id) },
                    onDelete = { pendingDelete = profile },
                )
                if (index < profiles.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            ActionPreference(
                title = stringResource(R.string.settings_streaming_new_profile),
                icon = Icons.Rounded.Add,
                onClick = { navigator.openTranscodeProfile(null) },
                showChevron = false,
            )
        }

        item(key = "how") {
            PreferenceCategory(title = null) {
                ExpandablePreference(title = stringResource(R.string.settings_streaming_how_title)) {
                    Text(
                        text = stringResource(R.string.settings_streaming_how_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    )
                }
            }
        }
    }

    pendingDelete?.let { profile ->
        ConfirmDialog(
            title = stringResource(R.string.settings_profile_delete_title, profile.name),
            text = stringResource(R.string.settings_profile_delete_text),
            confirmLabel = stringResource(R.string.settings_delete),
            onConfirm = {
                viewModel.deleteProfile(profile.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/** Preference row that opens a [ProfilePickerDialog]. */
@Composable
fun ProfilePreference(
    title: String,
    profiles: List<TranscodeProfile>,
    selectedId: String?,
    noneLabel: String?,
    onSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val selected = selectedId?.let { id -> profiles.firstOrNull { it.id == id } }
    val summary = when {
        selected != null -> "${selected.name} · ${selected.summary}"
        noneLabel != null -> noneLabel
        else -> TranscodeProfile.ORIGINAL.name
    }
    PreferenceRow(
        title = title,
        summary = summary,
        icon = icon,
        enabled = enabled,
        onClick = { open = true },
        modifier = modifier,
    )
    if (open) {
        ProfilePickerDialog(
            title = title,
            profiles = profiles,
            selectedId = selected?.id,
            noneLabel = noneLabel,
            onSelect = {
                open = false
                onSelected(it)
            },
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun ProfileRow(
    profile: TranscodeProfile,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    PreferenceRow(
        title = profile.name,
        summary = profile.summary,
        onClick = onEdit,
        trailing = {
            if (profile.builtIn) {
                Icon(
                    Icons.Rounded.Lock,
                    contentDescription = stringResource(R.string.settings_profile_builtin),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.settings_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_edit)) },
                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_delete)) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun NowCard(state: StreamingUiState, onRestore: () -> Unit) {
    val degraded = state.latencyDegraded
    val container = if (degraded) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer
    val content = if (degraded) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(if (state.isLive) R.string.settings_streaming_now else R.string.settings_streaming_now_preview),
                style = MaterialTheme.typography.labelLarge,
            )
            InfoLine(icon = networkIcon(state.network), text = networkLabel(state.network))
            InfoLine(icon = outputIcon(state.outputTarget), text = state.outputTarget.displayName)
            HorizontalDivider(color = content.copy(alpha = 0.2f))
            Text(state.decision.profile.name, style = MaterialTheme.typography.titleLarge)
            Text(
                text = "${state.decision.profile.summary} — ${state.decision.reason.label}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.decision.cappedByLatency) {
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(stringResource(R.string.settings_streaming_capped)) },
                    leadingIcon = { Icon(Icons.Rounded.Warning, contentDescription = null, Modifier.size(18.dp)) },
                    colors = AssistChipDefaults.assistChipColors(
                        disabledContainerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f),
                        disabledLabelColor = content,
                        disabledLeadingIconContentColor = content,
                    ),
                )
            }
            if (degraded) {
                Spacer(Modifier.height(2.dp))
                FilledTonalButton(onClick = onRestore) {
                    Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_streaming_restore))
                }
                Text(
                    text = stringResource(R.string.settings_streaming_degraded_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun InfoLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
