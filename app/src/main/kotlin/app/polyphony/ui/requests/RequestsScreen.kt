package app.polyphony.ui.requests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polyphony.R
import app.polyphony.core.domain.model.MusicRequest
import app.polyphony.core.domain.model.RequestAction
import app.polyphony.core.ui.components.ArtworkImage
import app.polyphony.core.ui.components.EmptyState
import app.polyphony.core.ui.components.QualityBadge
import app.polyphony.core.ui.components.listContentPadding
import app.polyphony.core.ui.theme.Dimens
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.shell.LocalSnackbarHostState

/** continuo requests (#117): what was asked for, where each one is, and what can be done. */
@Composable
fun RequestsScreen(viewModel: RequestsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val snackbar = LocalSnackbarHostState.current
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val listState = rememberLazyListState()
    var confirmRemove by remember { mutableStateOf<MusicRequest?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { m ->
            snackbar.showSnackbar(if (m.arg == null) context.getString(m.text) else context.getString(m.text, m.arg))
        }
    }
    // Opened from a "needs a look" notification: scroll to that request once it's listed.
    LaunchedEffect(state.focusId, state.requests) {
        val id = state.focusId ?: return@LaunchedEffect
        val index = state.requests.indexOfFirst { it.id == id }
        if (index >= 0) {
            listState.animateScrollToItem(index)
            viewModel.consumeFocus()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.requests_title)) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.back() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.browse_action_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::reload) {
                            Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.requests_action_refresh))
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
                if (state.linked) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Dimens.screenPadding)
                            .padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = state.scope == RequestScope.ACTIVE,
                            onClick = { viewModel.setScope(RequestScope.ACTIVE) },
                            label = { Text(stringResource(R.string.requests_scope_active)) },
                        )
                        FilterChip(
                            selected = state.scope == RequestScope.ALL,
                            onClick = { viewModel.setScope(RequestScope.ALL) },
                            label = { Text(stringResource(R.string.requests_scope_all)) },
                        )
                    }
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when {
            !state.linked -> Column(modifier) {
                EmptyState(title = stringResource(R.string.requests_title), body = stringResource(R.string.requests_not_linked), icon = Icons.Rounded.LibraryAdd)
            }
            state.needsSignIn -> Column(modifier) {
                EmptyState(title = stringResource(R.string.requests_title), body = stringResource(R.string.requests_sign_in), icon = Icons.Rounded.CloudOff)
            }
            state.requests.isEmpty() && state.failed -> Column(modifier) {
                EmptyState(
                    title = stringResource(R.string.requests_title),
                    body = stringResource(R.string.requests_failed),
                    icon = Icons.Rounded.CloudOff,
                    actionLabel = stringResource(R.string.requests_action_retry),
                    onAction = viewModel::reload,
                )
            }
            state.requests.isEmpty() && !state.loading -> Column(modifier) {
                EmptyState(
                    title = stringResource(R.string.requests_empty_title),
                    body = stringResource(
                        if (state.scope == RequestScope.ACTIVE) R.string.requests_empty_active_body else R.string.requests_empty_body,
                    ),
                    icon = Icons.Rounded.LibraryAdd,
                )
            }
            else -> LazyColumn(
                state = listState,
                modifier = modifier.fillMaxSize(),
                contentPadding = listContentPadding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(Dimens.itemSpacing),
            ) {
                items(state.requests, key = { it.id }, contentType = { "request" }) { request ->
                    RequestCard(
                        request = request,
                        busy = request.id in state.busy,
                        onOpen = { id -> navigator.openAlbum(id) },
                        onAction = { action ->
                            if (action == RequestAction.REMOVE) confirmRemove = request else viewModel.perform(request, action)
                        },
                    )
                }
            }
        }
    }

    confirmRemove?.let { request ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(R.string.requests_remove_title)) },
            text = { Text(stringResource(R.string.requests_remove_body, request.title)) },
            confirmButton = {
                TextButton(onClick = { confirmRemove = null; viewModel.perform(request, RequestAction.REMOVE) }) {
                    Text(stringResource(R.string.requests_action_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.requests_cancel)) }
            },
        )
    }
}

@Composable
private fun RequestCard(
    request: MusicRequest,
    busy: Boolean,
    onOpen: (String) -> Unit,
    onAction: (RequestAction) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.screenPadding),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArtworkImage(url = request.artworkUrl, contentDescription = null, modifier = Modifier.size(Dimens.listArtwork))
                Spacer(Modifier.width(Dimens.itemSpacing))
                Column(Modifier.weight(1f)) {
                    Text(request.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val subtitle = listOfNotNull(request.artist, request.year?.toString()).joinToString(" · ")
                    if (subtitle.isNotBlank()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                        QualityBadge(stringResource(stageLabel(request)))
                        if (request.stillLooking) QualityBadge(stringResource(R.string.requests_still_looking))
                        when (request.pipeline?.source) {
                            "soulseek" -> QualityBadge(stringResource(R.string.requests_source_soulseek))
                            "torrent" -> QualityBadge(stringResource(R.string.requests_source_torrent))
                        }
                        if (request.pipeline?.stalled == true) QualityBadge(stringResource(R.string.requests_stalled))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { RequestLogic.progress(request) },
                modifier = Modifier.fillMaxWidth(),
            )
            val detail = request.error ?: request.pipeline?.detail
            if (!detail.isNullOrBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (request.status == "error" || request.status == "needs_attention") MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            request.requestedBy?.let {
                Text(
                    stringResource(R.string.requests_by, it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(top = 6.dp),
            ) {
                val albumId = request.libraryAlbumId
                if (request.status == "available" && albumId != null) {
                    FilledTonalButton(onClick = { onOpen(albumId) }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Text(stringResource(R.string.requests_action_open))
                    }
                }
                RequestLogic.actions(request).forEach { action ->
                    TextButton(onClick = { onAction(action) }, enabled = !busy) {
                        Text(
                            stringResource(
                                when (action) {
                                    RequestAction.SEARCH_NOW -> R.string.requests_action_search_now
                                    RequestAction.RETRY -> R.string.requests_action_retry
                                    RequestAction.DROP_DOWNLOAD -> R.string.requests_action_drop
                                    RequestAction.REMOVE -> R.string.requests_action_remove
                                },
                            ),
                            color = if (action == RequestAction.REMOVE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

private fun stageLabel(r: MusicRequest): Int = when (r.status) {
    "pending" -> R.string.requests_stage_requested
    "searching" -> R.string.requests_stage_searching
    "downloading" -> R.string.requests_stage_downloading
    "importing" -> R.string.requests_stage_importing
    "scanning" -> R.string.requests_stage_scanning
    "available" -> R.string.requests_stage_available
    "error" -> R.string.requests_stage_error
    "needs_attention" -> R.string.requests_stage_needs_attention
    else -> R.string.requests_stage_requested
}
