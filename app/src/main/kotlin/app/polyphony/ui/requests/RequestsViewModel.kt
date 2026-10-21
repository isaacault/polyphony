package app.polyphony.ui.requests

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.polyphony.R
import app.polyphony.core.domain.model.MusicRequest
import app.polyphony.core.domain.model.RequestAction
import app.polyphony.core.domain.model.RequestEvent
import app.polyphony.core.domain.provider.ProviderException
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.navigation.RequestsRoute
import app.polyphony.ui.common.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RequestsUiState(
    val loading: Boolean = true,
    /** False when the active server isn't continuo-linked (the screen explains instead). */
    val linked: Boolean = true,
    val scope: RequestScope = RequestScope.ACTIVE,
    val requests: ImmutableList<MusicRequest> = persistentListOf(),
    val failed: Boolean = false,
    val needsSignIn: Boolean = false,
    /** Requests with an action in flight. */
    val busy: ImmutableSet<Long> = persistentSetOf(),
    val focusId: Long? = null,
)

@HiltViewModel
class RequestsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val servers: ServerRepository,
    private val requests: RequestRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(RequestsUiState(focusId = savedStateHandle.toRoute<RequestsRoute>().focusId))
    val state: StateFlow<RequestsUiState> = _state

    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    val events: Flow<UiMessage> = messages.receiveAsFlow()

    private var loadJob: Job? = null

    init {
        // Load and stream only while the screen is on show (someone collects [state]): the stream
        // closes when the user leaves, and coming back reloads and reopens it.
        viewModelScope.launch {
            _state.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { visible ->
                if (visible) stream()
            }
        }
    }

    fun setScope(scope: RequestScope) {
        if (_state.value.scope == scope) return
        _state.update { it.copy(scope = scope) }
        reload()
    }

    fun consumeFocus() = _state.update { it.copy(focusId = null) }

    fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val server = servers.getActive()
            if (!requests.supports(server) || server == null) {
                _state.update { it.copy(loading = false, linked = false, requests = persistentListOf()) }
                return@launch
            }
            _state.update { it.copy(loading = it.requests.isEmpty(), linked = true, failed = false) }
            try {
                val list = requests.requests(server, activeOnly = _state.value.scope == RequestScope.ACTIVE)
                _state.update { it.copy(loading = false, requests = list.toImmutableList(), failed = false, needsSignIn = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderException.Unauthorized) {
                _state.update { it.copy(loading = false, needsSignIn = true) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    private suspend fun stream() {
        reload()
        val server = servers.getActive()?.takeIf { requests.supports(it) } ?: return
        try {
            requests.events(server).collect { event ->
                when (event) {
                    is RequestEvent.Updated -> {
                        val next = RequestLogic.apply(_state.value.requests, event)
                        if (next == null) reload() else _state.update { it.copy(requests = next.toImmutableList()) }
                    }
                    RequestEvent.Resync -> reload()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderException.Unauthorized) {
            _state.update { it.copy(needsSignIn = true) }
        } catch (e: Exception) {
            // The stream retries on its own; anything escaping it means updates stop until the
            // screen is reopened. The list still reloads after each action.
        }
    }

    fun perform(request: MusicRequest, action: RequestAction) {
        if (request.id in _state.value.busy) return
        _state.update { it.copy(busy = (it.busy + request.id).toImmutableSet()) }
        viewModelScope.launch {
            try {
                val server = servers.getActive()?.takeIf { requests.supports(it) } ?: return@launch
                val message = requests.perform(server, request.id, action)
                if (action == RequestAction.REMOVE) {
                    _state.update { s -> s.copy(requests = s.requests.filterNot { it.id == request.id }.toImmutableList()) }
                }
                messages.send(
                    if (message.isBlank()) UiMessage(R.string.requests_action_done) else UiMessage(R.string.requests_message, message),
                )
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderException.Unauthorized) {
                messages.send(UiMessage(R.string.browse_request_sign_in))
            } catch (e: Exception) {
                messages.send(UiMessage(R.string.browse_request_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                _state.update { it.copy(busy = (it.busy - request.id).toImmutableSet()) }
            }
        }
    }
}
