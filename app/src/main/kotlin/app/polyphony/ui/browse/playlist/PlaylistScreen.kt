package app.polyphony.ui.browse.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polyphony.R
import app.polyphony.core.domain.model.Track
import app.polyphony.core.ui.components.ArtworkMosaic
import app.polyphony.core.ui.components.EmptyState
import app.polyphony.core.ui.components.ErrorState
import app.polyphony.core.ui.components.HeroHeader
import app.polyphony.core.ui.components.HeroScaffold
import app.polyphony.core.ui.components.LoadingPlaceholder
import app.polyphony.core.ui.components.OverlayIconButton
import app.polyphony.core.ui.components.PlaceholderStyle
import app.polyphony.core.ui.components.PlayShuffleButtons
import app.polyphony.core.ui.components.TrackRow
import app.polyphony.core.ui.theme.ArtworkShapes
import app.polyphony.core.ui.theme.Dimens
import app.polyphony.core.ui.util.formatDurationLong
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.common.CollectionDownloadButton
import app.polyphony.ui.common.ConfirmDialog
import app.polyphony.ui.common.rememberDownloadedIds
import app.polyphony.ui.common.RenameDialog
import app.polyphony.ui.common.TrackOptionsSheet

@Composable
fun PlaylistScreen(viewModel: PlaylistViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val listState = rememberLazyListState()
    var menuOpen by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var trackForOptions by remember { mutableStateOf<Pair<Int, Track>?>(null) }
    val playlist = state.playlist
    val downloadedIds by rememberDownloadedIds()
    val trackIds = remember(state.tracks) { state.tracks.map { it.id }.distinct() }

    LaunchedEffect(state.deleted) { if (state.deleted) navigator.back() }

    HeroScaffold(
        title = playlist?.name ?: "",
        listState = listState,
        onBack = { navigator.back() },
        actions = {
            if (playlist != null) {
                CollectionDownloadButton(trackIds = trackIds, tracks = { viewModel.state.value.tracks })
                OverlayIconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.browse_action_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browse_action_play_next)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null) },
                        onClick = { menuOpen = false; viewModel.playNext() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browse_action_add_to_queue)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) },
                        onClick = { menuOpen = false; viewModel.addToQueue() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browse_action_rename)) },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        onClick = { menuOpen = false; showRename = true },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browse_action_delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; showDelete = true },
                    )
                }
            }
        },
    ) { padding ->
        when {
            playlist == null && state.refreshFailed -> Column(Modifier.fillMaxSize().padding(top = 96.dp)) {
                ErrorState(message = stringResource(R.string.browse_error_playlist_unavailable), onRetry = viewModel::refresh)
            }
            playlist == null -> Column(Modifier.fillMaxSize().padding(top = 96.dp)) { LoadingPlaceholder(PlaceholderStyle.LIST) }
            else -> {
                val mosaic = remember(state.tracks) { state.tracks.map { it.artworkUrl }.filterNotNull().distinct().take(4) }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = padding) {
                    item(key = "hero", contentType = "hero") {
                        HeroHeader(
                            artworkUrl = playlist.artworkUrl ?: mosaic.firstOrNull(),
                            title = playlist.name,
                            artwork = if (playlist.artworkUrl == null && mosaic.size >= 4) {
                                { mod -> ArtworkMosaic(urls = mosaic, modifier = mod, shape = ArtworkShapes.hero) }
                            } else null,
                            subtitle = if (!playlist.description.isNullOrBlank()) {
                                {
                                    Text(
                                        playlist.description.orEmpty(),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 24.dp),
                                        maxLines = 2,
                                    )
                                }
                            } else null,
                            meta = buildList {
                                playlist.owner?.let { add(it) }
                                val count = state.tracks.size.takeIf { it > 0 } ?: playlist.trackCount
                                add(pluralStringResource(R.plurals.browse_count_songs, count, count))
                                val duration = state.totalDurationMs.takeIf { it > 0 } ?: playlist.durationMs
                                if (duration > 0) add(formatDurationLong(duration))
                                add(stringResource(if (playlist.isPublic) R.string.browse_playlist_public else R.string.browse_playlist_private))
                            }.joinToString(" · "),
                            belowTitle = {
                                PlayShuffleButtons(
                                    onPlay = { viewModel.play() },
                                    onShuffle = { viewModel.play(shuffle = true) },
                                    playLabel = stringResource(R.string.browse_action_play),
                                    shuffleLabel = stringResource(R.string.browse_action_shuffle),
                                    enabled = state.tracks.isNotEmpty(),
                                )
                            },
                        )
                    }
                    if (state.tracks.isEmpty()) {
                        item(key = "empty") {
                            if (playlist.trackCount == 0 || state.refreshFailed) {
                                EmptyState(
                                    title = stringResource(R.string.browse_empty_playlist_title),
                                    body = stringResource(R.string.browse_empty_playlist_body),
                                    icon = Icons.AutoMirrored.Rounded.QueueMusic,
                                )
                            } else {
                                LoadingPlaceholder(PlaceholderStyle.LIST, count = playlist.trackCount.coerceIn(3, 12))
                            }
                        }
                    }
                    itemsIndexed(state.tracks, key = { index, track -> "$index-${track.id}" }, contentType = { _, _ -> "track" }) { index, track ->
                        DismissibleTrackRow(
                            onDismiss = { viewModel.removeAt(index) },
                        ) {
                            TrackRow(
                                track = track,
                                onClick = { viewModel.play(startIndex = index) },
                                isCurrent = track.id == state.currentTrackId,
                                isPlaying = state.isPlaying,
                                isDownloaded = track.id in downloadedIds,
                                onLongClick = { trackForOptions = index to track },
                                onMoreClick = { trackForOptions = index to track },
                                modifier = Modifier.background(MaterialTheme.colorScheme.surface),
                            )
                        }
                    }
                    item(key = "footer") { Spacer(Modifier.height(Dimens.sectionSpacing)) }
                }
            }
        }
    }

    trackForOptions?.let { (index, track) ->
        TrackOptionsSheet(
            track = track,
            onDismiss = { trackForOptions = null },
            extraItems = { dismiss ->
                app.polyphony.ui.common.SheetItem(Icons.Rounded.Delete, stringResource(R.string.browse_action_remove_from_playlist)) {
                    dismiss(); viewModel.removeAt(index)
                }
            },
        )
    }
    if (showRename && playlist != null) {
        RenameDialog(
            title = stringResource(R.string.browse_action_rename),
            initialValue = playlist.name,
            label = stringResource(R.string.browse_label_playlist_name),
            onConfirm = viewModel::rename,
            onDismiss = { showRename = false },
        )
    }
    if (showDelete && playlist != null) {
        ConfirmDialog(
            title = stringResource(R.string.browse_title_delete_playlist),
            text = stringResource(R.string.browse_confirm_delete_playlist, playlist.name),
            confirmLabel = stringResource(R.string.browse_action_delete),
            destructive = true,
            onConfirm = viewModel::delete,
            onDismiss = { showDelete = false },
        )
    }
}

@Composable
private fun DismissibleTrackRow(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) { onDismiss(); true } else false
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) { content() }
}
