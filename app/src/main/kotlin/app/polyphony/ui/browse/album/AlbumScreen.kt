package app.polyphony.ui.browse.album

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polyphony.ui.common.FilledStar
import app.polyphony.ui.common.RatingMenu
import androidx.compose.foundation.layout.Box
import app.polyphony.R
import app.polyphony.core.domain.model.Track
import app.polyphony.core.ui.components.EmptyState
import app.polyphony.core.ui.components.ErrorState
import app.polyphony.core.ui.components.HeroHeader
import app.polyphony.core.ui.components.HeroScaffold
import app.polyphony.core.ui.components.LoadingPlaceholder
import app.polyphony.core.ui.components.OverlayIconButton
import app.polyphony.core.ui.components.PlaceholderStyle
import app.polyphony.core.ui.components.PlayShuffleButtons
import app.polyphony.core.ui.components.TrackRow
import app.polyphony.core.ui.theme.Dimens
import app.polyphony.core.ui.util.formatDurationLong
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.common.TrackSwipeRow
import app.polyphony.ui.common.AddToPlaylistSheet
import app.polyphony.ui.common.CollectionDownloadButton
import app.polyphony.ui.common.rememberDownloadedIds
import app.polyphony.ui.common.TrackOptionsSheet

@Composable
fun AlbumScreen(viewModel: AlbumViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    var menuOpen by remember { mutableStateOf(false) }
    var trackForOptions by remember { mutableStateOf<Track?>(null) }
    var showAddToPlaylist by remember { mutableStateOf(false) }
    val album = state.album
    val downloadedIds by rememberDownloadedIds()
    val trackIds = remember(state.tracks) { state.tracks.map { it.id } }

    val feedback by viewModel.feedback.collectAsStateWithLifecycle()
    var rateOpen by remember { mutableStateOf(false) }
    HeroScaffold(
        title = album?.name ?: "",
        listState = listState,
        onBack = { navigator.back() },
        actions = {
            if (album != null) {
                CollectionDownloadButton(trackIds = trackIds, tracks = { viewModel.state.value.tracks })
                if (feedback.usesRatings) {
                    Box {
                        OverlayIconButton(onClick = { rateOpen = true }) {
                            FilledStar(
                                rating = album.rating,
                                idleTint = MaterialTheme.colorScheme.onSurface,
                                contentDescription = stringResource(R.string.feedback_rate),
                            )
                        }
                        RatingMenu(expanded = rateOpen, rating = album.rating, onRate = viewModel::rate, onDismiss = { rateOpen = false })
                    }
                } else {
                    OverlayIconButton(onClick = {
                        haptics.performHapticFeedback(if (album.starred) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                        viewModel.toggleFavorite()
                    }) {
                        Icon(
                            if (album.starred) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = stringResource(R.string.browse_action_add_favorite),
                            tint = if (album.starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
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
                        text = { Text(stringResource(R.string.browse_action_add_to_playlist)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) },
                        onClick = { menuOpen = false; showAddToPlaylist = true },
                    )
                    if (album.artistId != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browse_action_go_to_artist)) },
                            leadingIcon = { Icon(Icons.Rounded.Person, null) },
                            onClick = { menuOpen = false; album.artistId?.let(navigator::openArtist) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        when {
            album == null && state.refreshFailed -> Column(Modifier.fillMaxSize().padding(top = 96.dp)) {
                ErrorState(message = stringResource(R.string.browse_error_album_unavailable), onRetry = viewModel::refresh)
            }
            album == null -> Column(Modifier.fillMaxSize().padding(top = 96.dp)) {
                LoadingPlaceholder(PlaceholderStyle.LIST)
            }
            else -> {
                val groups = remember(state.tracks) { state.tracks.groupBy { it.discNumber ?: 1 }.toSortedMap() }
                val multiDisc = groups.size > 1
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = padding,
                ) {
                    item(key = "hero", contentType = "hero") {
                        HeroHeader(
                            artworkUrl = album.artworkUrl,
                            title = album.name,
                            subtitle = {
                                Text(
                                    text = album.artistName ?: stringResource(R.string.browse_unknown_artist),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .padding(horizontal = 24.dp)
                                        .clickable(enabled = album.artistId != null) { album.artistId?.let(navigator::openArtist) },
                                )
                            },
                            meta = buildList {
                                album.year?.let { add(it.toString()) }
                                album.genre?.let { add(it) }
                                val count = state.tracks.size.takeIf { it > 0 } ?: album.trackCount
                                if (count > 0) add(pluralStringResource(R.plurals.browse_count_songs, count, count))
                                val duration = state.totalDurationMs.takeIf { it > 0 } ?: album.durationMs
                                if (duration > 0) add(formatDurationLong(duration))
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
                            when {
                                state.refreshFailed -> ErrorState(message = stringResource(R.string.browse_error_tracks_unavailable), onRetry = viewModel::refresh)
                                state.refreshDone -> EmptyState(title = stringResource(R.string.browse_empty_album_tracks))
                                else -> LoadingPlaceholder(PlaceholderStyle.LIST, count = album.trackCount.coerceIn(3, 12))
                            }
                        }
                    }
                    groups.forEach { (disc, tracks) ->
                        if (multiDisc) {
                            item(key = "disc-$disc", contentType = "disc") {
                                Text(
                                    text = stringResource(R.string.browse_disc_header, disc),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = Dimens.screenPadding, top = 16.dp, bottom = 4.dp),
                                )
                            }
                        }
                        items(tracks, key = { it.id }, contentType = { "track" }) { track ->
                            TrackSwipeRow(track = track) {
                                TrackRow(
                                    track = track,
                                    onClick = { viewModel.play(startIndex = state.tracks.indexOf(track)) },
                                    isCurrent = track.id == state.currentTrackId,
                                    isPlaying = state.isPlaying,
                                    showArtwork = !state.showTrackNumbers,
                                    subtitle = if (track.artistName != null && track.artistName != album.artistName) track.artistName else null,
                                    isDownloaded = track.id in downloadedIds,
                                    onLongClick = { trackForOptions = track },
                                    onMoreClick = { trackForOptions = track },
                                )
                            }
                        }
                    }
                    item(key = "footer") { Spacer(Modifier.height(Dimens.sectionSpacing)) }
                }
            }
        }
    }

    trackForOptions?.let { TrackOptionsSheet(track = it, onDismiss = { trackForOptions = null }) }
    if (showAddToPlaylist && state.tracks.isNotEmpty()) {
        AddToPlaylistSheet(trackIds = state.tracks.map { it.id }, onDismiss = { showAddToPlaylist = false })
    }
}
