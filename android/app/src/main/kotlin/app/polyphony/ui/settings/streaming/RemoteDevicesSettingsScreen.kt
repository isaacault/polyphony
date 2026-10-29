package app.polyphony.ui.settings.streaming

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.polyphony.R
import app.polyphony.core.domain.playback.CastController
import app.polyphony.core.domain.playback.PlaybackController
import app.polyphony.core.domain.repository.RemoteDevicePreference
import app.polyphony.core.domain.repository.TranscodeProfileRepository
import app.polyphony.core.domain.transcode.TranscodeProfile
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.settings.components.ConfirmDialog
import app.polyphony.ui.settings.components.InfoBanner
import app.polyphony.ui.settings.components.PreferenceCategory
import app.polyphony.ui.settings.components.PreferenceRow
import app.polyphony.ui.settings.components.ProfilePickerDialog
import app.polyphony.ui.settings.components.SettingsScaffold
import app.polyphony.ui.settings.components.relativeTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RemoteDevicesUiState(
    val devices: ImmutableList<RemoteDevicePreference> = persistentListOf(),
    val profiles: ImmutableList<TranscodeProfile> = persistentListOf(),
    val connectedKey: String? = null,
    val loaded: Boolean = false,
)

@HiltViewModel
class RemoteDevicesViewModel @Inject constructor(
    private val profileRepository: TranscodeProfileRepository,
    private val playbackController: PlaybackController,
    castController: CastController,
) : ViewModel() {

    val uiState: StateFlow<RemoteDevicesUiState> = combine(
        profileRepository.remoteDevices,
        profileRepository.profiles,
        castController.state,
    ) { devices, profiles, cast ->
        val connectedKey = cast.target?.key?.takeIf { cast.isConnected }
        RemoteDevicesUiState(
            devices = devices
                .sortedWith(
                    compareByDescending<RemoteDevicePreference> { it.targetKey == connectedKey }
                        .thenByDescending { it.lastConnectedAt ?: 0L }
                        .thenByDescending { it.lastSeenAt },
                )
                .toImmutableList(),
            profiles = profiles.toImmutableList(),
            connectedKey = connectedKey,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RemoteDevicesUiState())

    fun setProfile(targetKey: String, profileId: String?) {
        viewModelScope.launch {
            profileRepository.setRemoteDeviceProfile(targetKey, profileId)
            playbackController.refreshTranscodeDecision()
        }
    }

    fun forget(targetKey: String) {
        viewModelScope.launch {
            profileRepository.forgetRemoteDevice(targetKey)
            playbackController.refreshTranscodeDecision()
        }
    }
}

@Composable
fun RemoteDevicesSettingsScreen(viewModel: RemoteDevicesViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<RemoteDevicePreference?>(null) }
    var forgetting by remember { mutableStateOf<RemoteDevicePreference?>(null) }

    SettingsScaffold(title = stringResource(R.string.settings_cast_devices), onBack = { navigator.back() }) {
        if (!state.loaded) return@SettingsScaffold

        if (state.devices.isEmpty()) {
            item(key = "empty") {
                InfoBanner(text = stringResource(R.string.settings_cast_devices_empty), icon = Icons.Rounded.Cast)
            }
        } else {
            item(key = "intro") {
                Text(
                    text = stringResource(R.string.settings_cast_devices_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            item(key = "list") {
                PreferenceCategory(title = stringResource(R.string.settings_cast_devices_known)) {
                    state.devices.forEachIndexed { index, device ->
                        DeviceRow(
                            device = device,
                            connected = device.targetKey == state.connectedKey,
                            profileName = device.profileId?.let { id -> state.profiles.firstOrNull { it.id == id }?.name },
                            onClick = { picking = device },
                            onForget = { forgetting = device },
                        )
                        if (index < state.devices.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
        item(key = "discovery") {
            InfoBanner(
                text = stringResource(R.string.settings_cast_devices_discovery),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    picking?.let { device ->
        ProfilePickerDialog(
            title = device.name,
            profiles = state.profiles,
            selectedId = device.profileId,
            noneLabel = stringResource(R.string.settings_cast_devices_default_rules),
            onSelect = { id ->
                viewModel.setProfile(device.targetKey, id)
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
    forgetting?.let { device ->
        ConfirmDialog(
            title = stringResource(R.string.settings_cast_devices_forget_title, device.name),
            text = stringResource(R.string.settings_cast_devices_forget_text),
            confirmLabel = stringResource(R.string.settings_cast_devices_forget),
            onConfirm = {
                viewModel.forget(device.targetKey)
                forgetting = null
            },
            onDismiss = { forgetting = null },
        )
    }
}

@Composable
private fun DeviceRow(
    device: RemoteDevicePreference,
    connected: Boolean,
    profileName: String?,
    onClick: () -> Unit,
    onForget: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val status = when {
        connected -> stringResource(R.string.settings_cast_devices_connected_now)
        device.lastConnectedAt != null -> stringResource(R.string.settings_cast_devices_last_connected, relativeTime(device.lastConnectedAt))
        else -> stringResource(R.string.settings_cast_devices_last_seen, relativeTime(device.lastSeenAt))
    }
    val kind = if (isDlnaKey(device.targetKey)) "UPnP / DLNA" else "Cast"
    val summary = listOfNotNull(kind, device.modelName?.takeIf { it.isNotBlank() }, status).joinToString(" · ")
    PreferenceRow(
        title = device.name,
        summary = summary,
        icon = remoteDeviceIcon(device.targetKey, device.modelName, device.name),
        onClick = onClick,
        tint = if (connected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Unspecified,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = profileName ?: stringResource(R.string.settings_cast_devices_default_rules_short),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (profileName != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.settings_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_cast_devices_forget)) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onForget()
                            },
                        )
                    }
                }
            }
        },
    )
}
