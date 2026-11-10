package app.polyphony.ui.settings.playback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.polyphony.R
import app.polyphony.core.domain.model.ScrobbleAccount
import app.polyphony.core.domain.model.ScrobbleService
import app.polyphony.core.domain.repository.ScrobbleAuthException
import app.polyphony.core.domain.repository.ScrobbleRepository
import app.polyphony.core.ui.theme.Dimens
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.settings.components.ActionPreference
import app.polyphony.ui.settings.components.ConfirmDialog
import app.polyphony.ui.settings.components.InfoBanner
import app.polyphony.ui.settings.components.PreferenceCategory
import app.polyphony.ui.settings.components.PreferenceNote
import app.polyphony.ui.settings.components.PreferenceRow
import app.polyphony.ui.settings.components.SettingsScaffold
import app.polyphony.ui.settings.components.SwitchPreference
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PhoneScrobblingUiState(
    val accounts: Map<ScrobbleService, ScrobbleAccount> = emptyMap(),
    val pending: Map<ScrobbleService, Int> = emptyMap(),
    val lastFmAvailable: Boolean = false,
)

/** Why the last connect didn't work, for the screen to say. */
data class ScrobbleProblem(val service: ScrobbleService, val refused: Boolean)

@HiltViewModel
class PhoneScrobblingViewModel @Inject constructor(
    private val repository: ScrobbleRepository,
) : ViewModel() {

    val state: StateFlow<PhoneScrobblingUiState> = combine(repository.accounts, repository.pending) { accounts, pending ->
        PhoneScrobblingUiState(accounts.associateBy { it.service }, pending, repository.lastFmAvailable)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PhoneScrobblingUiState(lastFmAvailable = repository.lastFmAvailable))

    private val _connecting = MutableStateFlow<ScrobbleService?>(null)
    val connecting: StateFlow<ScrobbleService?> = _connecting.asStateFlow()
    private val _problem = MutableStateFlow<ScrobbleProblem?>(null)
    val problem: StateFlow<ScrobbleProblem?> = _problem.asStateFlow()

    fun connectListenBrainz(token: String) = connect(ScrobbleService.LISTENBRAINZ) { repository.connectListenBrainz(token) }

    fun connectLastFm(username: String, password: String) = connect(ScrobbleService.LASTFM) { repository.connectLastFm(username, password) }

    private fun connect(service: ScrobbleService, block: suspend () -> Unit) {
        viewModelScope.launch {
            _connecting.value = service
            _problem.value = null
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ScrobbleAuthException) {
                _problem.value = ScrobbleProblem(service, refused = true)
            } catch (e: Exception) {
                _problem.value = ScrobbleProblem(service, refused = false)
            } finally {
                _connecting.value = null
            }
        }
    }

    fun disconnect(service: ScrobbleService) {
        viewModelScope.launch { repository.disconnect(service) }
    }

    fun setNowPlaying(service: ScrobbleService, enabled: Boolean) {
        viewModelScope.launch { repository.setNowPlaying(service, enabled) }
    }
}

private val ScrobbleService.label: Int
    get() = when (this) {
        ScrobbleService.LISTENBRAINZ -> R.string.scrobble_listenbrainz
        ScrobbleService.LASTFM -> R.string.scrobble_lastfm
    }

/** The row in Playback, Scrobbling that leads here, saying which accounts are connected. */
@Composable
internal fun PhoneScrobblingRow(viewModel: PhoneScrobblingViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val connected = state.accounts.values.map { stringResource(it.service.label) }
    ActionPreference(
        title = stringResource(R.string.phone_scrobbling),
        summary = if (connected.isEmpty()) stringResource(R.string.phone_scrobbling_off)
        else stringResource(R.string.phone_scrobbling_on, connected.joinToString(", ")),
        onClick = { navigator.openPhoneScrobblingSettings() },
    )
}

/**
 * Scrobbling straight from this phone (#39): connect or disconnect ListenBrainz (user token) and
 * Last.fm (user name and password, only in a build with a Last.fm API key), now playing per account,
 * and how many plays wait to be sent. Opt-in and labelled as the one thing that leaves the user's
 * own server.
 */
@Composable
fun PhoneScrobblingScreen(viewModel: PhoneScrobblingViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val connecting by viewModel.connecting.collectAsStateWithLifecycle()
    val problem by viewModel.problem.collectAsStateWithLifecycle()
    var asking by rememberSaveable { mutableStateOf<ScrobbleService?>(null) }
    var disconnecting by rememberSaveable { mutableStateOf<ScrobbleService?>(null) }

    val services = buildList {
        add(ScrobbleService.LISTENBRAINZ)
        if (state.lastFmAvailable) add(ScrobbleService.LASTFM)
    }

    SettingsScaffold(title = stringResource(R.string.phone_scrobbling), onBack = { navigator.back() }) {
        item(key = "privacy", contentType = "banner") {
            InfoBanner(text = stringResource(R.string.phone_scrobbling_privacy), icon = Icons.Rounded.Shield)
        }
        services.forEach { service ->
            item(key = service.name, contentType = "category") {
                PreferenceCategory(title = stringResource(service.label)) {
                    ServiceRows(
                        service = service,
                        account = state.accounts[service],
                        pending = state.pending[service] ?: 0,
                        connecting = connecting == service,
                        problem = problem?.takeIf { it.service == service },
                        onConnect = { asking = service },
                        onDisconnect = { disconnecting = service },
                        onNowPlaying = { viewModel.setNowPlaying(service, it) },
                    )
                }
            }
        }
        item(key = "twice", contentType = "note") {
            PreferenceNote(stringResource(R.string.phone_scrobbling_twice))
        }
    }

    when (asking) {
        ScrobbleService.LISTENBRAINZ -> ListenBrainzTokenDialog(
            onConnect = { asking = null; viewModel.connectListenBrainz(it) },
            onDismiss = { asking = null },
        )
        ScrobbleService.LASTFM -> LastFmSignInDialog(
            initialUser = state.accounts[ScrobbleService.LASTFM]?.username.orEmpty(),
            onConnect = { user, password -> asking = null; viewModel.connectLastFm(user, password) },
            onDismiss = { asking = null },
        )
        null -> Unit
    }
    disconnecting?.let { service ->
        val name = stringResource(service.label)
        ConfirmDialog(
            title = name,
            text = stringResource(R.string.phone_scrobbling_disconnect_text, name),
            confirmLabel = stringResource(R.string.phone_scrobbling_disconnect),
            onConfirm = { disconnecting = null; viewModel.disconnect(service) },
            onDismiss = { disconnecting = null },
        )
    }
}

@Composable
private fun ServiceRows(
    service: ScrobbleService,
    account: ScrobbleAccount?,
    pending: Int,
    connecting: Boolean,
    problem: ScrobbleProblem?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onNowPlaying: (Boolean) -> Unit,
) {
    val name = stringResource(service.label)
    when {
        connecting -> PreferenceRow(
            title = stringResource(R.string.phone_scrobbling_connect),
            summary = stringResource(R.string.phone_scrobbling_connecting),
            enabled = false,
        )
        account == null -> ActionPreference(
            title = stringResource(R.string.phone_scrobbling_connect),
            summary = stringResource(
                if (service == ScrobbleService.LASTFM) R.string.phone_scrobbling_lastfm_summary else R.string.phone_scrobbling_listenbrainz_summary,
            ),
            onClick = onConnect,
        )
        account.needsReconnect -> ActionPreference(
            title = stringResource(R.string.phone_scrobbling_connected_as, account.username),
            summary = stringResource(R.string.phone_scrobbling_refused, name),
            onClick = onConnect,
        )
        else -> PreferenceRow(
            title = stringResource(R.string.phone_scrobbling_connected_as, account.username),
            summary = if (pending > 0) pluralStringResource(R.plurals.phone_scrobbling_pending, pending, pending) else null,
        )
    }
    if (account != null) {
        SwitchPreference(
            title = stringResource(R.string.phone_scrobbling_now_playing),
            summary = stringResource(R.string.phone_scrobbling_now_playing_summary),
            checked = account.nowPlaying,
            onCheckedChange = onNowPlaying,
        )
        ActionPreference(
            title = stringResource(R.string.phone_scrobbling_disconnect),
            onClick = onDisconnect,
            showChevron = false,
            destructive = true,
        )
    }
    problem?.let {
        PreferenceNote(stringResource(if (it.refused) R.string.phone_scrobbling_auth_failed else R.string.phone_scrobbling_unreachable, name))
    }
}

@Composable
private fun ListenBrainzTokenDialog(onConnect: (String) -> Unit, onDismiss: () -> Unit) {
    // Not saveable: the token isn't written into the saved instance state.
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scrobble_listenbrainz)) },
        text = {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(stringResource(R.string.scrobble_listenbrainz_token)) },
                supportingText = { Text(stringResource(R.string.scrobble_listenbrainz_token_hint)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        },
        confirmButton = {
            TextButton(enabled = token.isNotBlank(), onClick = { onConnect(token.trim()) }) {
                Text(stringResource(R.string.phone_scrobbling_connect))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

@Composable
private fun LastFmSignInDialog(initialUser: String, onConnect: (String, String) -> Unit, onDismiss: () -> Unit) {
    var user by rememberSaveable { mutableStateOf(initialUser) }
    // Not saveable: the password isn't written into the saved instance state.
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scrobble_lastfm)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.tightSpacing)) {
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text(stringResource(R.string.phone_scrobbling_lastfm_username)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.phone_scrobbling_lastfm_password)) },
                    supportingText = { Text(stringResource(R.string.phone_scrobbling_lastfm_password_hint)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = user.isNotBlank() && password.isNotEmpty(), onClick = { onConnect(user.trim(), password) }) {
                Text(stringResource(R.string.phone_scrobbling_connect))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}
