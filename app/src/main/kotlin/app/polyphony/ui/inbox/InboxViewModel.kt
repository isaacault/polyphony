package app.polyphony.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.polyphony.R
import app.polyphony.core.domain.model.InboxNotification
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.provider.ProviderException
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.inbox.InboxBadges
import app.polyphony.inbox.InboxCursors
import app.polyphony.inbox.InboxLogic
import app.polyphony.inbox.InboxTarget
import app.polyphony.inbox.newestNotifications
import app.polyphony.ui.common.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InboxUiState(
    val loading: Boolean = true,
    /** False when the active server isn't continuo-linked. */
    val linked: Boolean = true,
    val needsSignIn: Boolean = false,
    val failed: Boolean = false,
    /** Newest first. */
    val items: ImmutableList<InboxNotification> = persistentListOf(),
) {
    val unread: Int get() = items.count { !it.read }
}

/** continuo's notification inbox (#118) for the active server. */
@HiltViewModel
class InboxViewModel @Inject constructor(
    private val servers: ServerRepository,
    private val requests: RequestRepository,
    private val badges: InboxBadges,
    private val cursors: InboxCursors,
) : ViewModel() {

    private val _state = MutableStateFlow(InboxUiState())
    val state: StateFlow<InboxUiState> = _state.asStateFlow()

    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    val events: Flow<UiMessage> = messages.receiveAsFlow()

    private var loadJob: Job? = null

    init {
        reload()
    }

    fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val server = linkedServer()
            if (server == null) {
                _state.update { it.copy(loading = false, linked = false, items = persistentListOf()) }
                return@launch
            }
            _state.update { it.copy(loading = true, linked = true, failed = false) }
            try {
                val page = requests.newestNotifications(server)
                val all = page.items
                badges.set(server.id, page.unreadCount)
                // What's on screen has been seen: the background poll mustn't post it again.
                all.maxOfOrNull { it.id }?.let { cursors.advance(server.id, it) }
                _state.update {
                    it.copy(loading = false, needsSignIn = false, failed = false, items = InboxLogic.newestFirst(all).toImmutableList())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderException.Unauthorized) {
                _state.update { it.copy(loading = false, needsSignIn = true) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    /** Marks [item] read (optimistically) and returns where tapping it goes. */
    fun open(item: InboxNotification): InboxTarget {
        if (!item.read) {
            _state.update { s -> s.copy(items = InboxLogic.markRead(s.items, setOf(item.id)).toImmutableList()) }
            viewModelScope.launch {
                val server = linkedServer() ?: return@launch
                badges.decrement(server.id)
                markRead(server) { requests.markRead(server, listOf(item.id)) }
            }
        }
        return InboxLogic.target(item)
    }

    fun markAllRead() {
        if (_state.value.unread == 0) return
        _state.update { s -> s.copy(items = InboxLogic.markRead(s.items, null).toImmutableList()) }
        viewModelScope.launch {
            val server = linkedServer() ?: return@launch
            badges.set(server.id, 0)
            markRead(server) { requests.markAllRead(server) }
        }
    }

    private suspend fun markRead(server: Server, call: suspend () -> Int) {
        try {
            badges.set(server.id, call())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderException.Unauthorized) {
            messages.send(UiMessage(R.string.browse_request_sign_in))
        } catch (e: Exception) {
            // Put the truth back: what's still unread on the server shows as unread again.
            messages.send(UiMessage(R.string.inbox_mark_failed, e.message ?: e.javaClass.simpleName))
            reload()
        }
    }

    private suspend fun linkedServer(): Server? = servers.getActive()?.takeIf { requests.supports(it) }
}
