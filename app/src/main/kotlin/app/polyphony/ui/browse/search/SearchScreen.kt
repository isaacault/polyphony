package app.polyphony.ui.browse.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polyphony.R
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Track
import app.polyphony.core.ui.components.AlbumRow
import app.polyphony.core.ui.components.ArtistRow
import app.polyphony.core.ui.components.EmptyState
import app.polyphony.core.ui.components.PlaylistRow
import app.polyphony.core.ui.components.SectionHeader
import app.polyphony.core.ui.components.TrackRow
import app.polyphony.core.ui.components.listContentPadding
import app.polyphony.core.ui.theme.Dimens
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.common.AlbumOptionsSheet
import app.polyphony.ui.common.OfflineModeBanner
import app.polyphony.ui.common.rememberDownloadedIds
import app.polyphony.ui.common.TrackOptionsSheet

@Composable
fun SearchScreen(viewModel: SearchViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var trackForOptions by remember { mutableStateOf<Track?>(null) }
    var albumForOptions by remember { mutableStateOf<Album?>(null) }
    val downloadedIds by rememberDownloadedIds()

    LaunchedEffect(Unit) { if (state.query.isBlank()) focusRequester.requestFocus() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = Dimens.screenPadding, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.browse_action_back))
                    }
                    TextField(
                        value = state.query,
                        onValueChange = viewModel::onQueryChange,
                        placeholder = { Text(stringResource(R.string.browse_search_hint)) },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        trailingIcon = {
                            if (state.query.isNotEmpty()) {
                                IconButton(onClick = viewModel::clear) {
                                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.browse_action_clear))
                                }
                            }
                        },
                        singleLine = true,
                        shape = CircleShape,
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { viewModel.submit(); keyboard?.hide() }),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                    )
                }
                OfflineModeBanner()
                if (state.isSearchingRemote) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                }
            }
        },
    ) { padding ->
        when {
            !state.hasQuery -> RecentSearches(
                state = state,
                onPick = { viewModel.onQueryChange(it); viewModel.searchRemote() },
                onRemove = viewModel::removeRecent,
                modifier = Modifier.padding(padding),
            )
            state.isEmpty && !state.isSearchingRemote -> Column(Modifier.padding(padding)) {
                EmptyState(
                    title = stringResource(R.string.browse_search_no_results),
                    body = if (state.remoteSearched) stringResource(R.string.browse_search_no_results_remote) else stringResource(R.string.browse_search_try_server),
                    icon = if (state.remoteFailed) Icons.Rounded.CloudOff else Icons.Rounded.SearchOff,
                    actionLabel = if (!state.remoteSearched) stringResource(R.string.browse_action_search_server) else null,
                    onAction = { viewModel.searchRemote(); keyboard?.hide() },
                )
            }
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = listContentPadding(top = 4.dp),
            ) {
                if (state.artists.isNotEmpty()) {
                    item(key = "h-artists") { SectionHeader(title = stringResource(R.string.browse_tab_artists)) }
                    items(state.artists, key = { "a-" + it.id }, contentType = { "artist" }) { artist ->
                        ArtistRow(artist, onClick = { viewModel.rememberSearch(state.query); navigator.openArtist(artist.id) })
                    }
                }
                if (state.albums.isNotEmpty()) {
                    item(key = "h-albums") { SectionHeader(title = stringResource(R.string.browse_tab_albums)) }
                    items(state.albums, key = { "al-" + it.id }, contentType = { "album" }) { album ->
                        AlbumRow(
                            album,
                            onClick = { viewModel.rememberSearch(state.query); navigator.openAlbum(album.id) },
                            onLongClick = { albumForOptions = album },
                            onMoreClick = { albumForOptions = album },
                        )
                    }
                }
                if (state.playlists.isNotEmpty()) {
                    item(key = "h-playlists") { SectionHeader(title = stringResource(R.string.browse_tab_playlists)) }
                    items(state.playlists, key = { "p-" + it.id }, contentType = { "playlist" }) { playlist ->
                        PlaylistRow(playlist, onClick = { viewModel.rememberSearch(state.query); navigator.openPlaylist(playlist.id) })
                    }
                }
                if (state.tracks.isNotEmpty()) {
                    item(key = "h-tracks") { SectionHeader(title = stringResource(R.string.browse_tab_songs)) }
                    items(state.tracks, key = { "t-" + it.id }, contentType = { "track" }) { track ->
                        TrackRow(
                            track = track,
                            onClick = { viewModel.playTrack(state.tracks.indexOf(track)) },
                            isCurrent = track.id == state.currentTrackId,
                            isPlaying = state.isPlaying,
                            isDownloaded = track.id in downloadedIds,
                            onLongClick = { trackForOptions = track },
                            onMoreClick = { trackForOptions = track },
                        )
                    }
                }
                item(key = "remote") {
                    Column(Modifier.fillMaxWidth().padding(Dimens.screenPadding), horizontalAlignment = Alignment.CenterHorizontally) {
                        when {
                            state.isSearchingRemote -> Text(
                                stringResource(R.string.browse_search_searching_server),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            state.remoteFailed -> Text(
                                stringResource(R.string.browse_search_server_failed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            state.remoteSearched -> Text(
                                stringResource(R.string.browse_search_includes_server),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            else -> FilledTonalButton(onClick = { viewModel.searchRemote(); keyboard?.hide() }) {
                                Text(stringResource(R.string.browse_action_search_server))
                            }
                        }
                    }
                }
            }
        }
    }
    trackForOptions?.let { TrackOptionsSheet(track = it, onDismiss = { trackForOptions = null }) }
    albumForOptions?.let { AlbumOptionsSheet(album = it, onDismiss = { albumForOptions = null }) }
}

@Composable
private fun RecentSearches(
    state: SearchUiState,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.recentSearches.isEmpty()) {
        Column(modifier.fillMaxSize()) {
            EmptyState(
                title = stringResource(R.string.browse_search_title),
                body = stringResource(R.string.browse_search_body),
                icon = Icons.Rounded.Search,
            )
        }
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = listContentPadding(top = 4.dp)) {
        item(key = "header") { SectionHeader(title = stringResource(R.string.browse_search_recent)) }
        items(state.recentSearches, key = { it }) { term ->
            ListItem(
                headlineContent = { Text(term) },
                leadingContent = { Icon(Icons.Rounded.History, contentDescription = null) },
                trailingContent = {
                    IconButton(onClick = { onRemove(term) }) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.browse_action_remove))
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { onPick(term) },
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}
