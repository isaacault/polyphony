package app.polyphony.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polyphony.R
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.PlayContext
import app.polyphony.core.domain.model.Track
import app.polyphony.core.ui.components.ArtworkImage
import app.polyphony.core.ui.components.albumSubtitle
import app.polyphony.core.ui.theme.ArtworkShapes
import app.polyphony.navigation.LocalNavigator

/** Overflow options for a single track. Self-contained: hosts its own ViewModel and sub-dialogs. */
@Composable
fun TrackOptionsSheet(
    track: Track,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    extraItems: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
) {
    val vm: TrackActionsViewModel = hiltViewModel()
    val navigator = LocalNavigator.current
    val haptics = LocalHapticFeedback.current
    var showAddToPlaylist by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }

    if (showAddToPlaylist) {
        AddToPlaylistSheet(trackIds = listOf(track.id), onDismiss = { showAddToPlaylist = false; onDismiss() })
        return
    }
    if (showInfo) {
        SongInfoDialog(track = track, onDismiss = { showInfo = false; onDismiss() })
        return
    }

    OptionsSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        header = {
            SheetHeader(artworkUrl = track.artworkUrl, title = track.title, subtitle = track.displayArtist)
        },
    ) {
        SheetItem(Icons.AutoMirrored.Rounded.PlaylistPlay, stringResource(R.string.browse_action_play_next)) {
            vm.playNext(listOf(track)); onDismiss()
        }
        SheetItem(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.browse_action_add_to_queue)) {
            vm.addToQueue(listOf(track)); onDismiss()
        }
        SheetItem(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.browse_action_add_to_playlist)) {
            showAddToPlaylist = true
        }
        SheetItem(
            if (track.starred) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            stringResource(if (track.starred) R.string.browse_action_remove_favorite else R.string.browse_action_add_favorite),
            tint = if (track.starred) MaterialTheme.colorScheme.primary else null,
        ) {
            haptics.performHapticFeedback(if (track.starred) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            vm.toggleStarred(track); onDismiss()
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        if (track.albumId != null) {
            SheetItem(Icons.Rounded.Album, stringResource(R.string.browse_action_go_to_album)) {
                onDismiss(); track.albumId?.let(navigator::openAlbum)
            }
        }
        if (track.artistId != null) {
            SheetItem(Icons.Rounded.Person, stringResource(R.string.browse_action_go_to_artist)) {
                onDismiss(); track.artistId?.let(navigator::openArtist)
            }
        }
        TrackDownloadSheetItems(track = track, dismiss = onDismiss)
        extraItems?.invoke(onDismiss)
        SheetItem(Icons.Rounded.Info, stringResource(R.string.browse_action_song_info)) { showInfo = true }
    }
}

/** Overflow options for an album. Loads tracks on demand for queue/playlist actions. */
@Composable
fun AlbumOptionsSheet(
    album: Album,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: TrackActionsViewModel = hiltViewModel()
    val navigator = LocalNavigator.current
    val haptics = LocalHapticFeedback.current
    var addToPlaylistIds by remember { mutableStateOf<List<String>?>(null) }

    addToPlaylistIds?.let { ids ->
        AddToPlaylistSheet(trackIds = ids, onDismiss = { addToPlaylistIds = null; onDismiss() })
        return
    }

    OptionsSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        header = { SheetHeader(artworkUrl = album.artworkUrl, title = album.name, subtitle = albumSubtitle(album)) },
    ) {
        SheetItem(Icons.Rounded.PlayArrow, stringResource(R.string.browse_action_play)) {
            vm.withAlbumTracks(album.id) { vm.play(it, title = album.name, context = PlayContext.album(album)) }; onDismiss()
        }
        SheetItem(Icons.Rounded.Shuffle, stringResource(R.string.browse_action_shuffle)) {
            vm.withAlbumTracks(album.id) { vm.play(it, shuffle = true, title = album.name, context = PlayContext.album(album)) }; onDismiss()
        }
        SheetItem(Icons.AutoMirrored.Rounded.PlaylistPlay, stringResource(R.string.browse_action_play_next)) {
            vm.withAlbumTracks(album.id) { vm.playNext(it) }; onDismiss()
        }
        SheetItem(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.browse_action_add_to_queue)) {
            vm.withAlbumTracks(album.id) { vm.addToQueue(it) }; onDismiss()
        }
        SheetItem(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.browse_action_add_to_playlist)) {
            vm.withAlbumTracks(album.id) { tracks -> addToPlaylistIds = tracks.map { it.id } }
        }
        SheetItem(
            if (album.starred) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            stringResource(if (album.starred) R.string.browse_action_remove_favorite else R.string.browse_action_add_favorite),
            tint = if (album.starred) MaterialTheme.colorScheme.primary else null,
        ) {
            haptics.performHapticFeedback(if (album.starred) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            vm.toggleStarred(album); onDismiss()
        }
        if (album.artistId != null) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            SheetItem(Icons.Rounded.Person, stringResource(R.string.browse_action_go_to_artist)) {
                onDismiss(); album.artistId?.let(navigator::openArtist)
            }
        }
    }
}

/** Pick a playlist (or create one) for the given tracks. */
@Composable
fun AddToPlaylistSheet(
    trackIds: List<String>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: TrackActionsViewModel = hiltViewModel()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }

    if (showCreate) {
        RenameDialog(
            title = stringResource(R.string.browse_title_new_playlist),
            label = stringResource(R.string.browse_label_playlist_name),
            confirmLabel = stringResource(R.string.browse_action_create),
            onConfirm = { name -> vm.createPlaylist(name, trackIds) },
            onDismiss = { showCreate = false; onDismiss() },
        )
        return
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        Text(
            text = stringResource(R.string.browse_title_add_to_playlist),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.navigationBarsPadding()) {
            item(key = "new") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.browse_action_new_playlist)) },
                    leadingContent = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { showCreate = true },
                )
            }
            items(playlists, key = { it.id }) { playlist ->
                ListItem(
                    headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            stringResource(R.string.browse_count_songs, playlist.trackCount),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    leadingContent = {
                        ArtworkImage(
                            url = playlist.artworkUrl,
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            shape = ArtworkShapes.listThumb,
                            placeholder = Icons.AutoMirrored.Rounded.QueueMusic,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {
                        vm.addToPlaylist(playlist.id, trackIds)
                        onDismiss()
                    },
                )
            }
            item(key = "spacer") { Spacer(Modifier.height(16.dp)) }
        }
    }
}

// ---- building blocks ----

@Composable
fun OptionsSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            if (header != null) {
                header()
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
            content()
        }
    }
}

@Composable
fun SheetHeader(artworkUrl: String?, title: String, subtitle: String?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(url = artworkUrl, contentDescription = null, modifier = Modifier.size(56.dp), shape = ArtworkShapes.listThumb)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun SheetItem(
    icon: ImageVector,
    label: String,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null, tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
