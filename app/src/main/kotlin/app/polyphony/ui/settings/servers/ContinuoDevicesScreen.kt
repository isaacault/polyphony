package app.polyphony.ui.settings.servers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.polyphony.R
import app.polyphony.core.domain.model.ContinuoDevice
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.provider.ProviderException
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.navigation.ContinuoDevicesRoute
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.settings.components.ActionPreference
import app.polyphony.ui.settings.components.ConfirmDialog
import app.polyphony.ui.settings.components.InfoBanner
import app.polyphony.ui.settings.components.PreferenceCategory
import app.polyphony.ui.settings.components.PreferenceNote
import app.polyphony.ui.settings.components.PreferenceRow
import app.polyphony.ui.settings.components.SettingsScaffold
import app.polyphony.ui.settings.components.relativeTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ContinuoDevicesUiState(
    val loading: Boolean = true,
    /** The server is gone, or isn't continuo-linked. */
    val missing: Boolean = false,
    /** continuo refused this phone's session (revoked elsewhere); it must sign in again. */
    val needsSignIn: Boolean = false,
    val failure: String? = null,
    val devices: ImmutableList<ContinuoDevice> = persistentListOf(),
    /** Devices being signed out. */
    val busy: ImmutableSet<String> = persistentSetOf(),
    val revokingOthers: Boolean = false,
) {
    val hasOthers: Boolean get() = devices.any { !it.current }
}

sealed interface ContinuoDevicesMessage {
    data class SignedOut(val name: String) : ContinuoDevicesMessage
    data class SignedOutOthers(val count: Int) : ContinuoDevicesMessage
    data class Failed(val reason: String?) : ContinuoDevicesMessage
}

/** A continuo-linked server's account (#119): the devices signed in to it, and signing them out. */
@HiltViewModel
class ContinuoDevicesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val servers: ServerRepository,
    private val requests: RequestRepository,
) : ViewModel() {

    private val serverId = savedStateHandle.toRoute<ContinuoDevicesRoute>().serverId

    private val _state = MutableStateFlow(ContinuoDevicesUiState())
    val state: StateFlow<ContinuoDevicesUiState> = _state.asStateFlow()

    private val _messages = Channel<ContinuoDevicesMessage>(Channel.BUFFERED)
    val messages: Flow<ContinuoDevicesMessage> = _messages.receiveAsFlow()

    init {
        reload()
    }

    private suspend fun server(): Server? =
        servers.get(serverId)?.takeIf { it.isContinuo && !it.needsContinuoSignIn }

    fun reload() {
        viewModelScope.launch {
            val stored = servers.get(serverId)?.takeIf { it.isContinuo }
            if (stored == null || stored.needsContinuoSignIn) {
                _state.update { it.copy(loading = false, missing = stored == null, needsSignIn = stored != null) }
                return@launch
            }
            val server: Server = stored
            _state.update { it.copy(loading = true, failure = null) }
            try {
                val devices = requests.devices(server)
                _state.update {
                    it.copy(loading = false, needsSignIn = false, devices = ContinuoDeviceLogic.ordered(devices).toImmutableList())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderException.Unauthorized) {
                _state.update { it.copy(loading = false, needsSignIn = true) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, failure = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun revoke(device: ContinuoDevice) {
        if (device.current || device.id in _state.value.busy) return
        _state.update { it.copy(busy = (it.busy + device.id).toImmutableSet()) }
        viewModelScope.launch {
            try {
                val server = server() ?: return@launch
                try {
                    requests.revokeDevice(server, device.id)
                } catch (e: ProviderException.NotFound) {
                    // Already signed out (from another device, or continuo's web page).
                }
                _state.update { s -> s.copy(devices = s.devices.filterNot { it.id == device.id }.toImmutableList()) }
                _messages.send(ContinuoDevicesMessage.SignedOut(device.name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(ContinuoDevicesMessage.Failed(e.message))
            } finally {
                _state.update { it.copy(busy = (it.busy - device.id).toImmutableSet()) }
            }
        }
    }

    fun revokeOthers() {
        if (_state.value.revokingOthers) return
        _state.update { it.copy(revokingOthers = true) }
        viewModelScope.launch {
            try {
                val server = server() ?: return@launch
                val count = requests.revokeOtherDevices(server)
                _state.update { s -> s.copy(devices = ContinuoDeviceLogic.afterRevokingOthers(s.devices).toImmutableList()) }
                _messages.send(ContinuoDevicesMessage.SignedOutOthers(count))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(ContinuoDevicesMessage.Failed(e.message))
            } finally {
                _state.update { it.copy(revokingOthers = false) }
            }
        }
    }
}

@Composable
fun ContinuoDevicesScreen(viewModel: ContinuoDevicesViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmRevoke by remember { mutableStateOf<ContinuoDevice?>(null) }
    var confirmOthers by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbar.showSnackbar(
                when (message) {
                    is ContinuoDevicesMessage.SignedOut -> context.getString(R.string.continuo_device_signed_out, message.name)
                    is ContinuoDevicesMessage.SignedOutOthers -> context.resources.getQuantityString(
                        R.plurals.continuo_devices_signed_out_others, message.count, message.count,
                    )
                    is ContinuoDevicesMessage.Failed -> context.getString(
                        R.string.continuo_devices_sign_out_failed,
                        message.reason ?: context.getString(R.string.settings_err_unknown),
                    )
                },
            )
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.continuo_account_title),
        onBack = { navigator.back() },
        snackbarHostState = snackbar,
    ) {
        when {
            state.missing -> item(key = "missing") {
                InfoBanner(text = stringResource(R.string.continuo_devices_missing))
            }
            state.needsSignIn -> item(key = "sign-in") {
                InfoBanner(
                    text = stringResource(R.string.continuo_devices_sign_in),
                    icon = Icons.Rounded.Error,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            else -> {
                state.failure?.let { failure ->
                    item(key = "failed") {
                        InfoBanner(
                            text = stringResource(R.string.continuo_devices_failed, failure),
                            icon = Icons.Rounded.Error,
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    item(key = "retry") {
                        TextButton(onClick = viewModel::reload) { Text(stringResource(R.string.continuo_devices_retry)) }
                    }
                }
                item(key = "devices") {
                    PreferenceCategory(title = stringResource(R.string.continuo_devices_title)) {
                        if (state.loading && state.devices.isEmpty()) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(12.dp))
                                Text(stringResource(R.string.continuo_devices_loading))
                            }
                        }
                        state.devices.forEachIndexed { index, device ->
                            DeviceRow(
                                device = device,
                                earlierInstall = ContinuoDeviceLogic.isEarlierInstall(device, state.devices),
                                busy = device.id in state.busy || state.revokingOthers,
                                onRevoke = { confirmRevoke = device },
                            )
                            if (index < state.devices.lastIndex) {
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            }
                        }
                        PreferenceNote(text = stringResource(R.string.continuo_devices_note))
                    }
                }
                if (state.hasOthers) {
                    item(key = "others") {
                        PreferenceCategory(title = null) {
                            ActionPreference(
                                title = stringResource(R.string.continuo_devices_sign_out_others),
                                summary = stringResource(R.string.continuo_devices_sign_out_others_summary),
                                icon = Icons.AutoMirrored.Rounded.Logout,
                                enabled = !state.revokingOthers,
                                showChevron = false,
                                destructive = true,
                                onClick = { confirmOthers = true },
                            )
                        }
                    }
                }
            }
        }
    }

    confirmRevoke?.let { device ->
        ConfirmDialog(
            title = stringResource(R.string.continuo_device_sign_out_title, device.name),
            text = stringResource(R.string.continuo_device_sign_out_text, device.name),
            confirmLabel = stringResource(R.string.continuo_device_sign_out),
            onConfirm = {
                viewModel.revoke(device)
                confirmRevoke = null
            },
            onDismiss = { confirmRevoke = null },
        )
    }
    if (confirmOthers) {
        ConfirmDialog(
            title = stringResource(R.string.continuo_devices_sign_out_others_title),
            text = stringResource(R.string.continuo_devices_sign_out_others_text),
            confirmLabel = stringResource(R.string.continuo_device_sign_out),
            onConfirm = {
                viewModel.revokeOthers()
                confirmOthers = false
            },
            onDismiss = { confirmOthers = false },
        )
    }
}

@Composable
private fun DeviceRow(
    device: ContinuoDevice,
    earlierInstall: Boolean,
    busy: Boolean,
    onRevoke: () -> Unit,
) {
    val seen = device.lastSeenAtMillis?.let { stringResource(R.string.continuo_device_last_seen, relativeTime(it)) }
    val signedIn = device.createdAtMillis?.let { stringResource(R.string.continuo_device_signed_in, relativeTime(it)) }
    val summary = listOfNotNull(
        if (device.current) stringResource(R.string.continuo_device_this) else seen,
        signedIn,
    ).joinToString(" · ")
    PreferenceRow(
        title = device.name,
        summary = if (earlierInstall) summary + "\n" + stringResource(R.string.continuo_device_earlier_install) else summary,
        icon = if (device.platform.equals("android", ignoreCase = true)) Icons.Rounded.PhoneAndroid else Icons.Rounded.Devices,
        tint = if (device.current) MaterialTheme.colorScheme.primary else Color.Unspecified,
        trailing = if (device.current) {
            null
        } else {
            {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = onRevoke) {
                            Icon(
                                Icons.AutoMirrored.Rounded.Logout,
                                contentDescription = stringResource(R.string.continuo_device_sign_out),
                            )
                        }
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
