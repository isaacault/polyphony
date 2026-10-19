package app.polyphony.ui.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.polyphony.core.domain.model.ServerOptions
import app.polyphony.core.domain.model.ServerType
import app.polyphony.core.domain.provider.ConnectRequest
import app.polyphony.core.domain.provider.ProviderException
import app.polyphony.core.domain.repository.LibrarySyncManager
import app.polyphony.core.domain.repository.ProviderRegistry
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.navigation.ConnectServerRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import javax.inject.Inject

data class HeaderRow(val id: Long, val name: String = "", val value: String = "")

sealed interface ConnectError {
    data object InvalidUrl : ConnectError
    data object MissingUsername : ConnectError
    data object Unauthorized : ConnectError
    data object NotFound : ConnectError
    data class Network(val detail: String) : ConnectError
    data class Server(val code: Int, val detail: String) : ConnectError
    data class Unsupported(val detail: String) : ConnectError
    data class Unknown(val detail: String) : ConnectError
}

data class ConnectFormState(
    val serverType: ServerType? = null,
    /** True when the type came from the route; back then leaves the screen instead of the step. */
    val typeFixed: Boolean = false,
    val name: String = "",
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val legacyAuth: Boolean = false,
    val trustSelfSigned: Boolean = false,
    val headers: List<HeaderRow> = emptyList(),
    val showAdvanced: Boolean = false,
    val isConnecting: Boolean = false,
    val error: ConnectError? = null,
) {
    val urlError: Boolean get() = error == ConnectError.InvalidUrl
    val usernameError: Boolean get() = error == ConnectError.MissingUsername
}

sealed interface ConnectEvent {
    /** [firstServer] = this was the first server ever added → finish onboarding. */
    data class Done(val firstServer: Boolean) : ConnectEvent
}

@HiltViewModel
class ConnectServerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val providerRegistry: ProviderRegistry,
    private val serverRepository: ServerRepository,
    private val settingsRepository: SettingsRepository,
    private val librarySyncManager: LibrarySyncManager,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<ConnectServerRoute>()
    private val initialType = route.serverType?.let { name -> ServerType.entries.firstOrNull { it.name == name } }

    private val _state = MutableStateFlow(ConnectFormState(serverType = initialType, typeFixed = initialType != null))
    val state: StateFlow<ConnectFormState> = _state

    private val _events = Channel<ConnectEvent>(Channel.BUFFERED)
    val events: Flow<ConnectEvent> = _events.receiveAsFlow()

    private var headerIds = 0L

    fun selectType(type: ServerType?) = _state.update { it.copy(serverType = type, error = null) }
    fun setName(value: String) = _state.update { it.copy(name = value) }
    fun setUrl(value: String) = _state.update { it.copy(url = value, error = if (it.urlError) null else it.error) }
    fun setUsername(value: String) = _state.update { it.copy(username = value, error = if (it.usernameError) null else it.error) }
    fun setPassword(value: String) = _state.update { it.copy(password = value) }
    fun setLegacyAuth(value: Boolean) = _state.update { it.copy(legacyAuth = value) }
    fun setTrustSelfSigned(value: Boolean) = _state.update { it.copy(trustSelfSigned = value) }
    fun toggleAdvanced() = _state.update { it.copy(showAdvanced = !it.showAdvanced) }
    fun addHeader() = _state.update { it.copy(headers = it.headers + HeaderRow(id = headerIds++)) }
    fun removeHeader(id: Long) = _state.update { s -> s.copy(headers = s.headers.filterNot { it.id == id }) }
    fun updateHeader(id: Long, name: String? = null, value: String? = null) = _state.update { s ->
        s.copy(headers = s.headers.map { if (it.id == id) it.copy(name = name ?: it.name, value = value ?: it.value) else it })
    }

    fun connect() {
        val form = _state.value
        val type = form.serverType ?: return
        val url = normalizeUrl(form.url)
        if (url == null) {
            _state.update { it.copy(error = ConnectError.InvalidUrl) }
            return
        }
        if (form.username.isBlank()) {
            _state.update { it.copy(error = ConnectError.MissingUsername) }
            return
        }
        _state.update { it.copy(isConnecting = true, error = null) }
        viewModelScope.launch {
            try {
                val firstServer = serverRepository.servers.first().isEmpty()
                val options = buildMap {
                    if (type == ServerType.SUBSONIC && form.legacyAuth) put(ServerOptions.LEGACY_AUTH, "true")
                    if (form.trustSelfSigned) put(ServerOptions.TRUST_SELF_SIGNED, "true")
                    val headers = form.headers.filter { it.name.isNotBlank() }
                    if (headers.isNotEmpty()) {
                        put(
                            ServerOptions.CUSTOM_HEADERS,
                            JsonObject(headers.associate { it.name.trim() to JsonPrimitive(it.value) }).toString(),
                        )
                    }
                }
                val request = ConnectRequest(
                    type = type,
                    name = form.name.trim().ifBlank { hostLabel(url) },
                    baseUrl = url,
                    username = form.username.trim(),
                    password = form.password,
                    options = options,
                )
                val server = providerRegistry.factoryFor(type).authenticate(request)
                serverRepository.add(server, makeActive = true)
                settingsRepository.update { it.copy(onboardingComplete = true) }
                syncScope.launch { runCatching { librarySyncManager.syncNow(full = true) } }
                _state.update { it.copy(isConnecting = false) }
                _events.send(ConnectEvent.Done(firstServer))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderException) {
                _state.update { it.copy(isConnecting = false, error = e.toConnectError()) }
            } catch (e: Exception) {
                _state.update { it.copy(isConnecting = false, error = ConnectError.Unknown(e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    private fun ProviderException.toConnectError(): ConnectError = when (this) {
        is ProviderException.Unauthorized -> ConnectError.Unauthorized
        is ProviderException.NotFound -> ConnectError.NotFound
        is ProviderException.Network -> ConnectError.Network(cause?.message ?: message ?: "")
        is ProviderException.Server -> ConnectError.Server(code, message ?: "")
        is ProviderException.Unsupported -> ConnectError.Unsupported(message ?: "")
        is ProviderException.Unknown -> ConnectError.Unknown(message ?: "")
    }

    companion object {
        /** Library sync outlives this screen, so it runs on an app-wide scope. */
        private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val LAN_HOST = Regex("""^(localhost|\d{1,3}(\.\d{1,3}){3}|[^/:]+\.(local|lan|home|internal))(:\d+)?$""")

        /** Adds a scheme when missing (http for LAN-looking hosts, https otherwise) and trims slashes. */
        fun normalizeUrl(raw: String): String? {
            var value = raw.trim()
            if (value.isEmpty()) return null
            if (!value.contains("://")) {
                val hostPart = value.substringBefore('/')
                value = (if (LAN_HOST.matches(hostPart)) "http://" else "https://") + value
            }
            value = value.trimEnd('/')
            return runCatching { URI(value) }.getOrNull()
                ?.takeIf { it.host != null && (it.scheme == "http" || it.scheme == "https") }
                ?.let { value }
        }

        fun hostLabel(url: String): String =
            runCatching { URI(url).host }.getOrNull()?.removePrefix("www.") ?: url
    }
}
