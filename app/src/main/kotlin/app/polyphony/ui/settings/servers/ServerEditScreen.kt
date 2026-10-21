package app.polyphony.ui.settings.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.polyphony.R
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerInfo
import app.polyphony.core.domain.model.ServerOptions
import app.polyphony.core.domain.model.ServerType
import app.polyphony.core.domain.provider.ConnectRequest
import app.polyphony.core.domain.repository.ProviderRegistry
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.navigation.LocalNavigator
import app.polyphony.navigation.ServerEditRoute
import app.polyphony.ui.settings.components.InfoBanner
import app.polyphony.ui.settings.components.PreferenceCategory
import app.polyphony.ui.settings.components.PreferenceNote
import app.polyphony.ui.settings.components.SettingsScaffold
import app.polyphony.core.domain.transcode.AudioCodec
import app.polyphony.core.playback.transcode.CodecSupport
import app.polyphony.core.playback.transcode.TranscodeCapabilityProbe
import app.polyphony.ui.settings.components.ActionPreference
import app.polyphony.ui.settings.components.relativeTime
import app.polyphony.core.playback.remote.ReceiverAddressCheck
import app.polyphony.ui.settings.components.SwitchPreference
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

data class HeaderEntry(val key: String = "", val value: String = "")

sealed interface ReceiverCheckState {
    data object Running : ReceiverCheckState
    data class Done(val outcome: ReceiverAddressCheck.Outcome) : ReceiverCheckState
}

sealed interface ConnectionTest {
    data object Running : ConnectionTest
    data class Success(val info: ServerInfo?) : ConnectionTest
    data class Failure(val error: Throwable) : ConnectionTest
}

data class ServerEditUiState(
    val loaded: Boolean = false,
    val notFound: Boolean = false,
    val type: ServerType = ServerType.SUBSONIC,
    val name: String = "",
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    /** A continuo server with no device session here (e.g. restored from a backup). */
    val needsContinuoSignIn: Boolean = false,
    val legacyAuth: Boolean = false,
    val trustSelfSigned: Boolean = false,
    val remoteBaseUrl: String = "",
    val remoteAutoLan: Boolean = true,
    val transcodeCodecs: Set<AudioCodec>? = null,
    val transcodeProbedAt: Long? = null,
    val probing: Boolean = false,
    val probeError: String? = null,
    val receiverCheck: ReceiverCheckState? = null,
    val headers: List<HeaderEntry> = emptyList(),
    val test: ConnectionTest? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
) {
    val isBusy: Boolean get() = saving || test is ConnectionTest.Running
    val canSubmit: Boolean get() = name.isNotBlank() && baseUrl.isNotBlank() && username.isNotBlank() && !isBusy
}

@HiltViewModel
class ServerEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverRepository: ServerRepository,
    private val providerRegistry: ProviderRegistry,
    private val transcodeProbe: TranscodeCapabilityProbe,
    private val receiverAddressCheck: ReceiverAddressCheck,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<ServerEditRoute>()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var original: Server? = null

    private val _state = MutableStateFlow(ServerEditUiState())
    val state: StateFlow<ServerEditUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val server = route.serverId?.let { serverRepository.get(it) }
            if (server == null) {
                _state.update { it.copy(loaded = true, notFound = true) }
            } else {
                original = server
                _state.update {
                    it.copy(
                        loaded = true,
                        type = server.type,
                        name = server.name,
                        baseUrl = server.baseUrl,
                        username = server.username,
                        needsContinuoSignIn = server.needsContinuoSignIn,
                        legacyAuth = server.options[ServerOptions.LEGACY_AUTH] == "true",
                        trustSelfSigned = server.options[ServerOptions.TRUST_SELF_SIGNED] == "true",
                        remoteBaseUrl = server.options[ServerOptions.REMOTE_BASE_URL].orEmpty(),
                        remoteAutoLan = server.options[ServerOptions.REMOTE_AUTO_LAN] != "false",
                        transcodeCodecs = CodecSupport.supported(server),
                        transcodeProbedAt = server.options[ServerOptions.TRANSCODE_PROBED_AT]?.toLongOrNull(),
                        headers = parseHeaders(server.options[ServerOptions.CUSTOM_HEADERS]),
                    )
                }
            }
        }
    }

    fun onName(value: String) = _state.update { it.copy(name = value, test = null) }
    fun onBaseUrl(value: String) = _state.update { it.copy(baseUrl = value, test = null) }
    fun onUsername(value: String) = _state.update { it.copy(username = value, test = null) }
    fun onPassword(value: String) = _state.update { it.copy(password = value, test = null) }
    fun onLegacyAuth(value: Boolean) = _state.update { it.copy(legacyAuth = value, test = null) }
    fun onTrustSelfSigned(value: Boolean) = _state.update { it.copy(trustSelfSigned = value, test = null) }
    fun probeTranscoding() {
        val server = original ?: return
        if (_state.value.probing) return
        viewModelScope.launch {
            _state.update { it.copy(probing = true, probeError = null) }
            val result = runCatching { transcodeProbe.probeAndStore(server) }
            // The probe rewrote the stored options; keep our copy current so a later Save keeps them.
            original = serverRepository.get(server.id) ?: server
            _state.update { st ->
                result.fold(
                    onSuccess = { r ->
                        if (r == null) st.copy(probing = false, probeError = "No songs synced yet; sync the library first.")
                        else st.copy(probing = false, transcodeCodecs = r.supported, transcodeProbedAt = r.probedAt)
                    },
                    onFailure = { e -> st.copy(probing = false, probeError = e.message ?: e.javaClass.simpleName) },
                )
            }
        }
    }

    fun onRemoteBaseUrl(value: String) = _state.update { it.copy(remoteBaseUrl = value, receiverCheck = null) }

    fun testReceiverAddress() {
        val server = original ?: return
        val address = _state.value.remoteBaseUrl.trim()
        if (address.isEmpty() || _state.value.receiverCheck is ReceiverCheckState.Running) return
        viewModelScope.launch {
            _state.update { it.copy(receiverCheck = ReceiverCheckState.Running) }
            val outcome = runCatching { receiverAddressCheck.check(server, address) }
                .getOrElse { e -> ReceiverAddressCheck.Outcome.Unreachable(address, e.message ?: e.javaClass.simpleName) }
            _state.update { it.copy(receiverCheck = ReceiverCheckState.Done(outcome)) }
        }
    }

    fun onRemoteAutoLan(value: Boolean) = _state.update { it.copy(remoteAutoLan = value) }

    fun addHeader() = _state.update { it.copy(headers = it.headers + HeaderEntry()) }
    fun removeHeader(index: Int) = _state.update { it.copy(headers = it.headers.filterIndexed { i, _ -> i != index }) }
    fun updateHeader(index: Int, entry: HeaderEntry) = _state.update {
        it.copy(headers = it.headers.mapIndexed { i, old -> if (i == index) entry else old }, test = null)
    }

    fun testConnection() {
        viewModelScope.launch {
            _state.update { it.copy(test = ConnectionTest.Running) }
            val result = runCatching { connect(_state.value) }
            _state.update {
                it.copy(
                    test = result.fold(
                        onSuccess = { (_, info) -> ConnectionTest.Success(info) },
                        onFailure = { e -> ConnectionTest.Failure(e) },
                    ),
                )
            }
        }
    }

    fun save() {
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            val result = runCatching { connect(_state.value) }
            result.fold(
                onSuccess = { (server, _) ->
                    serverRepository.update(server)
                    providerRegistry.invalidate(server.id)
                    _state.update { it.copy(saving = false, saved = true) }
                },
                onFailure = { e ->
                    _state.update { it.copy(saving = false, test = ConnectionTest.Failure(e)) }
                },
            )
        }
    }

    /** Validates the form against the server and returns the server to persist plus its info. */
    private suspend fun connect(form: ServerEditUiState): Pair<Server, ServerInfo?> {
        val base = original ?: error("Server not loaded")
        val options = buildOptions(base, form)
        val url = normalizeBaseUrl(form.baseUrl)
        val factory = providerRegistry.factoryFor(base.type)
        return if (form.password.isBlank()) {
            val candidate = base.copy(
                name = form.name.trim(),
                baseUrl = url,
                username = form.username.trim(),
                options = options,
            )
            val info = factory.create(candidate).ping()
            candidate to info
        } else {
            val authed = factory.authenticate(
                ConnectRequest(
                    type = base.type,
                    name = form.name.trim(),
                    baseUrl = url,
                    username = form.username.trim(),
                    password = form.password,
                    options = options,
                ),
            )
            // A continuo sign-in issues a new device: its id and token must replace the old ones
            // carried over in `options`, or the new app password would sit next to a stale session.
            val merged = authed.copy(
                id = base.id,
                createdAt = base.createdAt,
                options = authed.options + (options - ServerOptions.CONTINUO_KEYS),
            )
            val info = runCatching { factory.create(merged).ping() }.getOrNull()
            merged to info
        }
    }

    private fun buildOptions(base: Server, form: ServerEditUiState): Map<String, String> {
        val managed = setOf(
            ServerOptions.LEGACY_AUTH, ServerOptions.TRUST_SELF_SIGNED, ServerOptions.CUSTOM_HEADERS,
            ServerOptions.REMOTE_BASE_URL, ServerOptions.REMOTE_AUTO_LAN,
        )
        return buildMap {
            base.options.filterKeys { it !in managed }.forEach { (k, v) -> put(k, v) }
            if (form.legacyAuth) put(ServerOptions.LEGACY_AUTH, "true")
            if (form.trustSelfSigned) put(ServerOptions.TRUST_SELF_SIGNED, "true")
            form.remoteBaseUrl.trim().takeIf { it.isNotEmpty() }?.let { put(ServerOptions.REMOTE_BASE_URL, it) }
            if (!form.remoteAutoLan) put(ServerOptions.REMOTE_AUTO_LAN, "false")
            val headers = form.headers.filter { it.key.isNotBlank() }
            if (headers.isNotEmpty()) {
                put(
                    ServerOptions.CUSTOM_HEADERS,
                    buildJsonObject { headers.forEach { put(it.key.trim(), JsonPrimitive(it.value)) } }.toString(),
                )
            }
        }
    }

    private fun parseHeaders(raw: String?): List<HeaderEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.parseToJsonElement(raw).jsonObject.map { (k, v) -> HeaderEntry(k, v.jsonPrimitive.content) }
        }.getOrDefault(emptyList())
    }
}

@Composable
fun ServerEditScreen(viewModel: ServerEditViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) navigator.back()
    }

    SettingsScaffold(
        title = stringResource(R.string.settings_server_edit),
        onBack = { navigator.back() },
        actions = {
            TextButton(onClick = viewModel::save, enabled = state.canSubmit && !state.notFound) {
                Text(stringResource(R.string.settings_save))
            }
        },
    ) {
        if (!state.loaded) return@SettingsScaffold
        if (state.notFound) {
            item(key = "missing") { InfoBanner(text = stringResource(R.string.settings_server_missing)) }
            return@SettingsScaffold
        }

        item(key = "connection") {
            PreferenceCategory(title = stringResource(R.string.settings_server_connection)) {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = state.type.displayName,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = state.name,
                        onValueChange = viewModel::onName,
                        label = { Text(stringResource(R.string.settings_server_name)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.baseUrl,
                        onValueChange = viewModel::onBaseUrl,
                        label = { Text(stringResource(R.string.settings_server_url)) },
                        placeholder = { Text("https://music.example.com") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.username,
                        onValueChange = viewModel::onUsername,
                        label = { Text(stringResource(R.string.settings_server_username)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PasswordField(
                        value = state.password,
                        onValueChange = viewModel::onPassword,
                        label = stringResource(R.string.settings_server_password),
                        supporting = stringResource(
                            if (state.needsContinuoSignIn) R.string.continuo_server_password_sign_in
                            else R.string.settings_server_password_keep,
                        ),
                    )
                }
            }
        }

        item(key = "transcoding") {
            PreferenceCategory(title = stringResource(R.string.settings_server_transcoding)) {
                val codecs = state.transcodeCodecs
                val summary = when {
                    state.probing -> stringResource(R.string.settings_server_transcoding_checking)
                    state.probeError != null -> state.probeError!!
                    codecs == null -> stringResource(R.string.settings_server_transcoding_not_checked)
                    codecs.isEmpty() -> stringResource(R.string.settings_server_transcoding_none, relativeTime(state.transcodeProbedAt))
                    else -> stringResource(
                        R.string.settings_server_transcoding_supported,
                        codecs.sortedBy { it.ordinal }.joinToString(", ") { it.label },
                        relativeTime(state.transcodeProbedAt),
                    )
                }
                ActionPreference(
                    title = stringResource(R.string.settings_server_transcoding_check),
                    summary = summary,
                    enabled = !state.probing && state.loaded && !state.notFound,
                    showChevron = false,
                    onClick = viewModel::probeTranscoding,
                )
                PreferenceNote(text = stringResource(R.string.settings_server_transcoding_note))
            }
        }

        item(key = "advanced") {
            PreferenceCategory(title = stringResource(R.string.settings_server_advanced)) {
                if (state.type == ServerType.SUBSONIC) {
                    SwitchPreference(
                        title = stringResource(R.string.settings_server_legacy_auth),
                        summary = stringResource(R.string.settings_server_legacy_auth_summary),
                        checked = state.legacyAuth,
                        onCheckedChange = viewModel::onLegacyAuth,
                    )
                }
                SwitchPreference(
                    title = stringResource(R.string.settings_server_trust_self_signed),
                    summary = stringResource(R.string.settings_server_trust_self_signed_summary),
                    checked = state.trustSelfSigned,
                    onCheckedChange = viewModel::onTrustSelfSigned,
                )
                OutlinedTextField(
                    value = state.remoteBaseUrl,
                    onValueChange = viewModel::onRemoteBaseUrl,
                    label = { Text(stringResource(R.string.settings_server_remote_url)) },
                    placeholder = { Text("http://192.168.1.10:4533") },
                    supportingText = { Text(stringResource(R.string.settings_server_remote_url_summary)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                val check = state.receiverCheck
                ActionPreference(
                    title = stringResource(R.string.settings_server_remote_test),
                    summary = when (check) {
                        null -> stringResource(R.string.settings_server_remote_test_summary)
                        ReceiverCheckState.Running -> stringResource(R.string.settings_server_remote_test_running)
                        is ReceiverCheckState.Done -> when (val o = check.outcome) {
                            is ReceiverAddressCheck.Outcome.Reachable ->
                                if (o.viaStream) stringResource(R.string.settings_server_remote_test_ok_stream, o.httpStatus, o.millis)
                                else stringResource(R.string.settings_server_remote_test_ok_host, o.httpStatus)
                            is ReceiverAddressCheck.Outcome.Unreachable -> stringResource(R.string.settings_server_remote_test_fail, o.reason)
                        }
                    },
                    enabled = state.remoteBaseUrl.isNotBlank() && check !is ReceiverCheckState.Running && state.loaded && !state.notFound,
                    showChevron = false,
                    onClick = viewModel::testReceiverAddress,
                )
                SwitchPreference(
                    title = stringResource(R.string.settings_server_remote_auto_lan),
                    summary = stringResource(R.string.settings_server_remote_auto_lan_summary),
                    checked = state.remoteAutoLan,
                    onCheckedChange = viewModel::onRemoteAutoLan,
                )
                HeadersEditor(
                    headers = state.headers,
                    onAdd = viewModel::addHeader,
                    onRemove = viewModel::removeHeader,
                    onUpdate = viewModel::updateHeader,
                )
            }
        }

        item(key = "test") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (val test = state.test) {
                    null -> Unit
                    ConnectionTest.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(12.dp))
                        Text(stringResource(R.string.settings_server_testing))
                    }
                    is ConnectionTest.Success -> InfoBanner(
                        text = test.info?.let {
                            stringResource(R.string.settings_server_test_ok_info, it.productName, it.version)
                        } ?: stringResource(R.string.settings_server_test_ok),
                        icon = Icons.Rounded.CheckCircle,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    is ConnectionTest.Failure -> InfoBanner(
                        text = providerErrorMessage(test.error),
                        icon = Icons.Rounded.Error,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = viewModel::testConnection,
                        enabled = state.canSubmit,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.settings_server_test)) }
                    Button(
                        onClick = viewModel::save,
                        enabled = state.canSubmit,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.settings_save)) }
                }
            }
        }
    }
}

@Composable
internal fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = stringResource(R.string.settings_server_toggle_password),
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
internal fun HeadersEditor(
    headers: List<HeaderEntry>,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    onUpdate: (Int, HeaderEntry) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.settings_server_headers), style = MaterialTheme.typography.bodyLarge)
        PreferenceNote(
            text = stringResource(R.string.settings_server_headers_summary),
            modifier = Modifier.padding(0.dp),
        )
        headers.forEachIndexed { index, entry ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                OutlinedTextField(
                    value = entry.key,
                    onValueChange = { onUpdate(index, entry.copy(key = it)) },
                    label = { Text(stringResource(R.string.settings_server_header_name)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = entry.value,
                    onValueChange = { onUpdate(index, entry.copy(value = it)) },
                    label = { Text(stringResource(R.string.settings_server_header_value)) },
                    singleLine = true,
                    modifier = Modifier.weight(1.4f),
                )
                IconButton(onClick = { onRemove(index) }) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.settings_remove))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onAdd) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.settings_server_add_header))
        }
    }
}
