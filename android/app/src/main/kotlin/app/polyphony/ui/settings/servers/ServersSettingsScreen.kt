package app.polyphony.ui.settings.servers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.polyphony.R
import app.polyphony.core.data.di.ApplicationScope
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerType
import app.polyphony.core.domain.repository.ProviderRegistry
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.settings.components.ConfirmDialog
import app.polyphony.ui.settings.components.InfoBanner
import app.polyphony.ui.settings.components.PreferenceCategory
import app.polyphony.ui.settings.components.PreferenceRow
import app.polyphony.ui.settings.components.SettingsScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ServersUiState(
    val servers: ImmutableList<Server> = persistentListOf(),
    val activeId: String? = null,
    val loaded: Boolean = false,
)

sealed interface ServersEvent {
    data object NoServersLeft : ServersEvent
}

@HiltViewModel
class ServersViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val providerRegistry: ProviderRegistry,
    private val settingsRepository: SettingsRepository,
    private val requests: RequestRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val _events = Channel<ServersEvent>(Channel.BUFFERED)
    val events: Flow<ServersEvent> = _events.receiveAsFlow()

    val uiState: StateFlow<ServersUiState> = combine(
        serverRepository.servers,
        serverRepository.activeServer,
    ) { servers, active ->
        ServersUiState(servers.toImmutableList(), active?.id, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServersUiState())

    fun setActive(id: String) {
        viewModelScope.launch { serverRepository.setActive(id) }
    }

    fun remove(id: String) {
        viewModelScope.launch {
            // #120: sign this phone's continuo device out too, best-effort and on the side, so
            // the removal never waits for continuo or fails because of it.
            serverRepository.get(id)?.takeIf { it.isContinuo && !it.needsContinuoSignIn }?.let { server ->
                appScope.launch { requests.signOut(server) }
            }
            val wasActive = serverRepository.getActive()?.id == id
            serverRepository.remove(id)
            providerRegistry.invalidate(id)
            val remaining = serverRepository.servers.first()
            if (remaining.isEmpty()) {
                settingsRepository.update { it.copy(onboardingComplete = false) }
                _events.send(ServersEvent.NoServersLeft)
            } else if (wasActive && serverRepository.getActive() == null) {
                serverRepository.setActive(remaining.first().id)
            }
        }
    }
}

@Composable
fun ServersSettingsScreen(viewModel: ServersViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingRemove by remember { mutableStateOf<Server?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                ServersEvent.NoServersLeft -> navigator.openOnboarding()
            }
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.settings_servers),
        onBack = { navigator.back() },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { navigator.openConnectServer(null) },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.settings_servers_add)) },
            )
        },
    ) {
        if (state.loaded && state.servers.isEmpty()) {
            item(key = "empty") {
                InfoBanner(text = stringResource(R.string.settings_servers_empty))
            }
        }
        if (state.servers.isNotEmpty()) {
            item(key = "list") {
                PreferenceCategory(title = stringResource(R.string.settings_servers_configured)) {
                    state.servers.forEachIndexed { index, server ->
                        ServerRow(
                            server = server,
                            isActive = server.id == state.activeId,
                            onSelect = { viewModel.setActive(server.id) },
                            onEdit = { navigator.openServerEdit(server.id) },
                            onDevices = { navigator.openContinuoDevices(server.id) },
                            onRemove = { pendingRemove = server },
                        )
                        if (index < state.servers.lastIndex) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
            item(key = "hint") {
                Text(
                    text = stringResource(R.string.settings_servers_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            }
        }
    }

    pendingRemove?.let { server ->
        ConfirmDialog(
            title = stringResource(R.string.settings_servers_remove_title, server.name),
            text = stringResource(
                if (server.isContinuo && !server.needsContinuoSignIn) R.string.continuo_server_remove_text
                else R.string.settings_servers_remove_text,
            ),
            confirmLabel = stringResource(R.string.settings_remove),
            onConfirm = {
                viewModel.remove(server.id)
                pendingRemove = null
            },
            onDismiss = { pendingRemove = null },
        )
    }
}

@Composable
private fun ServerRow(
    server: Server,
    isActive: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDevices: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    PreferenceRow(
        title = server.name,
        summary = if (server.needsContinuoSignIn) {
            "${server.displayHost} · ${stringResource(R.string.continuo_server_needs_sign_in)}"
        } else {
            "${server.type.displayName} · ${server.displayHost}"
        },
        icon = when (server.type) {
            ServerType.SUBSONIC -> Icons.Rounded.Storage
            ServerType.JELLYFIN -> Icons.Rounded.Dns
        },
        onClick = onSelect,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = isActive, onClick = onSelect)
                Box {
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
                        if (server.isContinuo && !server.needsContinuoSignIn) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.continuo_account_title)) },
                                leadingIcon = { Icon(Icons.Rounded.Devices, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onDevices()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_remove)) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onRemove()
                            },
                        )
                    }
                }
            }
        },
    )
}
