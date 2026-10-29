package app.polyphony.ui.inbox

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polyphony.R
import app.polyphony.core.domain.model.InboxNotification
import app.polyphony.core.ui.components.EmptyState
import app.polyphony.core.ui.components.listContentPadding
import app.polyphony.core.ui.theme.Dimens
import app.polyphony.inbox.InboxTarget
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.shell.LocalSnackbarHostState

/** continuo's notification inbox (#118): what became available, needs a look, or failed. */
@Composable
fun InboxScreen(viewModel: InboxViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val snackbar = LocalSnackbarHostState.current
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { m ->
            snackbar.showSnackbar(if (m.arg == null) context.getString(m.text) else context.getString(m.text, m.arg))
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.inbox_title)) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.back() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.browse_action_back))
                        }
                    },
                    actions = {
                        if (state.linked) {
                            IconButton(onClick = viewModel::markAllRead, enabled = state.unread > 0) {
                                Icon(Icons.Rounded.DoneAll, contentDescription = stringResource(R.string.inbox_mark_all_read))
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when {
            !state.linked -> Column(modifier) {
                EmptyState(title = stringResource(R.string.inbox_title), body = stringResource(R.string.requests_not_linked), icon = Icons.Rounded.Inbox)
            }
            state.needsSignIn -> Column(modifier) {
                EmptyState(title = stringResource(R.string.inbox_title), body = stringResource(R.string.requests_sign_in), icon = Icons.Rounded.CloudOff)
            }
            state.items.isEmpty() && state.failed -> Column(modifier) {
                EmptyState(
                    title = stringResource(R.string.inbox_title),
                    body = stringResource(R.string.inbox_failed),
                    icon = Icons.Rounded.CloudOff,
                    actionLabel = stringResource(R.string.requests_action_retry),
                    onAction = viewModel::reload,
                )
            }
            state.items.isEmpty() && !state.loading -> Column(modifier) {
                EmptyState(
                    title = stringResource(R.string.inbox_empty_title),
                    body = stringResource(R.string.inbox_empty_body),
                    icon = Icons.Rounded.Inbox,
                )
            }
            else -> LazyColumn(
                modifier = modifier.fillMaxSize(),
                contentPadding = listContentPadding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(Dimens.tightSpacing),
            ) {
                items(state.items, key = { it.id }, contentType = { "notification" }) { item ->
                    NotificationRow(
                        item = item,
                        onClick = {
                            when (val target = viewModel.open(item)) {
                                is InboxTarget.Album -> navigator.openAlbum(target.albumId)
                                is InboxTarget.Request -> navigator.openRequests(target.id)
                                InboxTarget.None -> Unit
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(item: InboxNotification, onClick: () -> Unit) {
    val unread = !item.read
    val unreadLabel = stringResource(R.string.inbox_unread)
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.screenPadding),
        shape = MaterialTheme.shapes.large,
        color = if (unread) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(
                        if (unread) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerHigh,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    typeIcon(item.type),
                    contentDescription = null,
                    tint = when (item.type) {
                        "request_available" -> MaterialTheme.colorScheme.primary
                        "request_needs_attention", "request_error" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(Dimens.itemSpacing))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    item.createdAtMillis?.let { millis ->
                        Text(
                            " · " + DateUtils.getRelativeTimeSpanString(
                                millis,
                                System.currentTimeMillis(),
                                DateUtils.MINUTE_IN_MILLIS,
                                DateUtils.FORMAT_ABBREV_RELATIVE,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                item.body?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (unread) {
                Spacer(Modifier.width(Dimens.tightSpacing))
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .size(8.dp)
                        .semantics { contentDescription = unreadLabel }
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
    }
}

private fun typeIcon(type: String): ImageVector = when (type) {
    "request_available" -> Icons.Rounded.LibraryMusic
    "request_needs_attention" -> Icons.Rounded.Warning
    "request_error" -> Icons.Rounded.ErrorOutline
    else -> Icons.Rounded.Notifications
}
