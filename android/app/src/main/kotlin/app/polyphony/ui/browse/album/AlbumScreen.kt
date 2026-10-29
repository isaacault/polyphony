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
import androidx.compose.material.icons.rounded.DeleteOutline
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polyphony.core.domain.model.CatalogTrack
import app.polyphony.ui.browse.catalog.CatalogRow
import app.polyphony.ui.browse.catalog.requestTrackLabel
import app.polyphony.ui.common.CatalogSongSheet
import app.polyphony.ui.common.CatalogSwipeRow
import app.polyphony.ui.common.FilledStar
import app.polyphony.ui.common.LibraryRemovalViewModel
import app.polyphony.ui.common.PlayNowViewModel
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
import app.polyphony.ui.common.RemoveAlbumDialog
import app.polyphony.ui.common.TrackSwipeRow
import app.polyphony.ui.common.AddToPlaylistSheet
import app.polyphony.ui.common.CollectionDownloadButton
import app.polyphony.ui.common.rememberDownloadedIds
import app.polyphony.ui.common.TrackOptionsSheet
import app.polyphony.ui.shell.LocalSnackbarHostState

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
    val playNow = hiltViewModel<PlayNowViewModel>().playNow
    val canPlayNow by playNow.available.collectAsStateWithLifecycle()
    var songForOptions by remember { mutableStateOf<CatalogTrack?>(null) }
    val snackbar = LocalSnackbarHostState.current
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { m ->
            snackbar.showSnackbar(if (m.arg == null) context.getString(m.text) else context.getString(m.text, m.arg))
        }
    }

    val feedback by viewModel.feedback.collectAsStateWithLifecycle()
    var rateOpen by remember { mutableStateOf(false) }
    val removal = hiltViewModel<LibraryRemovalViewModel>().removal
    val canRemove by removal.canRemove.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf(false) }
    if (confirmRemove && album != null) {
        RemoveAlbumDialog(
            album = album,
            // The album leaves this phone's library too, so its page closes behind it.
            onConfirm = { confirmRemove = false; removal.remove(album); navigator.back() },
            onDismiss = { confirmRemove = false },
        )
    }
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
                    if (canRemove) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_remove), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; confirmRemove = true },
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
                // The library's songs, among the rest of the album where it lacks some (AlbumTracklist).
                val groups = remember(state.entries) { state.entries.groupBy { it.disc }.toSortedMap() }
                val multiDisc = groups.size > 1
                // Shown once in the header; a track's own chip only where it differs.
                val albumQuality = remember(state.tracks) { AlbumTrackDetails.albumQuality(state.tracks) }
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
                                albumQuality?.let { add(it) }
                            }.joinToString(" · "),
                            belowTitle = {
                                PlayShuffleButtons(
                                    onPlay = { viewModel.playEntry(0) },
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
                        items(tracks, key = { e -> when (e) { is AlbumEntry.Have -> e.track.id; is AlbumEntry.Missing -> "missing-" + e.track.key } },
                              contentType = { if (it is AlbumEntry.Have) "track" else "catalog-track" }) { entry ->
                            if (entry is AlbumEntry.Missing) {
                                val song = entry.track
                                // Not in the library: Play now plays it, Request fetches it, swipes queue it.
                                CatalogSwipeRow(song) {
                                    CatalogRow(
                                        title = song.title,
                                        subtitle = null,
                                        artworkUrl = null,
                                        availability = song.availability,
                                        releaseGroupId = song.albumReleaseGroupId,
                                        busy = song.requestKeys.any { it in state.requesting },
                                        requestLabel = stringResource(requestTrackLabel(song)),
                                        onRequest = { viewModel.requestTrack(song) },
                                        // The album from here: this song, and the rest after it (polyphony: whole-album queue).
                                        onClick = if (canPlayNow && song.recordingId != null) ({ viewModel.playEntry(state.entries.indexOf(entry)) }) else null,
                                        onLongClick = { songForOptions = song },
                                    )
                                }
                                return@items
                            }
                            val track = (entry as AlbumEntry.Have).track
                            TrackSwipeRow(track = track) {
                                TrackRow(
                                    track = track,
                                    onClick = { viewModel.playEntry(state.entries.indexOf(entry)) },
                                    isCurrent = track.id == state.currentTrackId,
                                    isPlaying = state.isPlaying,
                                    showArtwork = !state.showTrackNumbers,
                                    // Only what the page doesn't already say: who's featured on this one.
                                    subtitle = when (val credit = AlbumTrackDetails.credit(track, album.artistName)) {
                                        is AlbumTrackDetails.Credit.Featuring -> stringResource(R.string.browse_track_featuring, credit.names)
                                        is AlbumTrackDetails.Credit.Other -> credit.artist
                                        null -> null
                                    },
                                    fallbackSubtitle = false,
                                    qualityLabel = AlbumTrackDetails.trackQuality(track, albumQuality),
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
    songForOptions?.let { CatalogSongSheet(track = it, onOpenAlbum = null, onDismiss = { songForOptions = null }) }
    if (showAddToPlaylist && state.tracks.isNotEmpty()) {
        AddToPlaylistSheet(trackIds = state.tracks.map { it.id }, onDismiss = { showAddToPlaylist = false })
    }
}
