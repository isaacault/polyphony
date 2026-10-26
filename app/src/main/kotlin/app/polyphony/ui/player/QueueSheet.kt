package app.polyphony.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polyphony.R
import app.polyphony.core.domain.model.Track
import app.polyphony.core.ui.components.ArtworkImage
import app.polyphony.core.ui.theme.ArtworkShapes
import app.polyphony.core.ui.util.formatDuration
import app.polyphony.core.ui.util.formatDurationLong
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private data class QueueItem(val key: String, val track: Track)

private fun List<Track>.toQueueItems(): List<QueueItem> {
    val seen = HashMap<String, Int>()
    return map { track ->
        val n = seen.getOrDefault(track.id, 0)
        seen[track.id] = n + 1
        QueueItem(key = "${track.id}#$n", track = track)
    }
}

/** "Up next": reorder by dragging the handle, swipe left to remove, tap to jump. */
@Composable
fun QueueSheet(
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit,
    onSaveAsPlaylist: () -> Unit,
) {
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val hapticsEnabled by viewModel.hapticsEnabled.collectAsStateWithLifecycle()
    val haptics = rememberHaptics(hapticsEnabled)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val snackHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var items by remember { mutableStateOf(playback.queue.toQueueItems()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(playback.queue, dragging) {
        if (!dragging) items = playback.queue.toQueueItems()
    }

    val listState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        items = items.toMutableList().apply { add(to.index, removeAt(from.index)) }
        viewModel.moveQueueItem(from.index, to.index)
        haptics.segmentTick()
    }

    LaunchedEffect(Unit) {
        val index = playback.currentIndex
        if (index in items.indices) listState.scrollToItem((index - 1).coerceAtLeast(0))
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxHeight(0.92f)) {
            QueueHeader(
                count = items.size,
                durationMs = items.sumOf { it.track.durationMs },
                queueTitle = playback.queueTitle,
                shuffleEnabled = playback.shuffleEnabled,
                onShuffle = viewModel::toggleShuffle,
                onClear = {
                    viewModel.clearQueue()
                    onDismiss()
                },
                onSave = onSaveAsPlaylist,
            )
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.queue_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    itemsIndexed(items, key = { _, item -> item.key }, contentType = { _, _ -> "queueRow" }) { index, item ->
                        ReorderableItem(reorderableState, key = item.key) { isDragging ->
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    if (value == SwipeToDismissBoxValue.EndToStart) {
                                        val removed = item.track
                                        val removedIndex = index
                                        items = items.toMutableList().apply { removeAt(index) }
                                        viewModel.removeFromQueue(index)
                                        haptics.gestureEnd()
                                        scope.launch {
                                            val result = snackHost.showSnackbar(
                                                message = context.getString(R.string.queue_removed, removed.title),
                                                actionLabel = context.getString(R.string.browse_action_undo),
                                                duration = SnackbarDuration.Short,
                                            )
                                            if (result == SnackbarResult.ActionPerformed) viewModel.insertIntoQueue(removedIndex, removed)
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                },
                            )
                            SwipeToDismissBox(
                                state = dismissState,
                                enableDismissFromStartToEnd = false,
                                backgroundContent = {
                                    // Only paint the delete affordance while a swipe is in progress,
                                    // so it never shows through the (translucent) highlighted row.
                                    val swiping = dismissState.targetValue != SwipeToDismissBoxValue.Settled ||
                                        dismissState.progress > 0f && dismissState.progress < 1f
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .background(if (swiping) MaterialTheme.colorScheme.errorContainer else Color.Transparent)
                                            .padding(end = 24.dp),
                                        contentAlignment = Alignment.CenterEnd,
                                    ) {
                                        if (!swiping) return@Box
                                        Icon(
                                            Icons.Rounded.Delete,
                                            contentDescription = stringResource(R.string.queue_remove),
                                            tint = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                    }
                                },
                            ) {
                                QueueRow(
                                    track = item.track,
                                    isCurrent = index == playback.currentIndex,
                                    isPlaying = playback.isPlaying,
                                    elevation = if (isDragging) 6.dp else 0.dp,
                                    onClick = { viewModel.skipToQueueItem(index) },
                                    handleModifier = Modifier.draggableHandle(
                                        onDragStarted = {
                                            dragging = true
                                            haptics.longPress()
                                        },
                                        onDragStopped = {
                                            dragging = false
                                            haptics.gestureEnd()
                                        },
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            SnackbarHost(snackHost, modifier = Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun QueueHeader(
    count: Int,
    durationMs: Long,
    queueTitle: String?,
    shuffleEnabled: Boolean,
    onShuffle: () -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.queue_title), style = MaterialTheme.typography.titleLarge)
            val summary = stringResource(R.string.queue_summary, count, formatDurationLong(durationMs))
            Text(
                text = queueTitle?.let { "$it · $summary" } ?: summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onShuffle) {
            Icon(
                Icons.Rounded.Shuffle,
                contentDescription = stringResource(R.string.player_shuffle),
                tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onSave, enabled = count > 0) {
            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = stringResource(R.string.queue_save))
        }
        IconButton(onClick = onClear, enabled = count > 0) {
            Icon(Icons.Rounded.ClearAll, contentDescription = stringResource(R.string.queue_clear))
        }
    }
}

@Composable
private fun QueueRow(
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    elevation: Dp,
    onClick: () -> Unit,
    handleModifier: Modifier,
) {
    Surface(
        color = if (isCurrent) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                .compositeOver(MaterialTheme.colorScheme.surfaceContainerLow)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        tonalElevation = elevation,
        shadowElevation = elevation,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                ArtworkImage(
                    url = track.artworkUrl,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    shape = ArtworkShapes.listThumb,
                )
                if (isCurrent) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f), ArtworkShapes.listThumb),
                        contentAlignment = Alignment.Center,
                    ) {
                        PlayingIndicatorBars(playing = isPlaying, color = MaterialTheme.colorScheme.inversePrimary)
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track.displayArtist,   // the artwork already says which album
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = formatDuration(track.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = Icons.Rounded.DragHandle,
                contentDescription = stringResource(R.string.queue_drag),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = handleModifier.padding(12.dp),
            )
        }
    }
}
