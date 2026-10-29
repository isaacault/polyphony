package app.polyphony.ui.inbox

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.polyphony.R
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.inbox.InboxBadges
import app.polyphony.navigation.LocalNavigator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** The active continuo server's unread inbox count, refreshed from continuo when first shown. */
@HiltViewModel
class InboxBadgeViewModel @Inject constructor(
    servers: ServerRepository,
    private val requests: RequestRepository,
    private val badges: InboxBadges,
) : ViewModel() {
    val unread: StateFlow<Int> = servers.activeServer
        .distinctUntilChanged { a, b -> a?.id == b?.id && requests.supports(a) == requests.supports(b) }
        .flatMapLatest { server ->
            if (server == null || !requests.supports(server)) {
                flowOf(0)
            } else {
                badges.unread(server.id).onStart {
                    try {
                        badges.set(server.id, requests.notifications(server, limit = 1).unreadCount)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Offline or signed out: keep the last known count.
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}

/** Top-bar entry to the inbox (#118), with the unread count as a badge. */
@Composable
fun InboxButton(viewModel: InboxBadgeViewModel = hiltViewModel()) {
    val unread by viewModel.unread.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val description = if (unread > 0) stringResource(R.string.inbox_open_unread, unread) else stringResource(R.string.inbox_open)
    IconButton(onClick = { navigator.openInbox() }) {
        BadgedBox(
            badge = {
                if (unread > 0) Badge { Text(if (unread > 99) "99+" else unread.toString()) }
            },
        ) {
            Icon(Icons.Rounded.Inbox, contentDescription = description)
        }
    }
}
