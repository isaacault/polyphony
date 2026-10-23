package app.polyphony.ui.browse.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
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
import app.polyphony.core.ui.components.MediaRow
import app.polyphony.core.ui.components.PlaylistRow
import app.polyphony.core.ui.components.SectionHeader
import app.polyphony.core.ui.components.TrackRow
import app.polyphony.core.ui.components.listContentPadding
import app.polyphony.core.ui.theme.Dimens
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.browse.catalog.CatalogArtistRow
import app.polyphony.ui.browse.catalog.CatalogRow
import app.polyphony.ui.browse.catalog.PartialBadge
import app.polyphony.ui.browse.LibraryFilterRow
import app.polyphony.ui.common.TrackSwipeRow
import app.polyphony.ui.common.AlbumOptionsSheet
import app.polyphony.ui.common.OfflineModeBanner
import app.polyphony.ui.common.rememberDownloadedIds
import app.polyphony.ui.common.TrackOptionsSheet
import app.polyphony.ui.shell.LocalSnackbarHostState

@Composable
fun SearchScreen(viewModel: SearchViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val genres by viewModel.genreNames.collectAsStateWithLifecycle()
    val years by viewModel.yearRange.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var trackForOptions by remember { mutableStateOf<Track?>(null) }
    var albumForOptions by remember { mutableStateOf<Album?>(null) }
    val downloadedIds by rememberDownloadedIds()
    val listState = rememberLazyListState()
    // Results arrive in waves (cache, server, catalogue) and a list keeps its first visible row
    // where it is, so new artists and albums landed above the view. Stay at the top until the
    // user drags the list themselves; a new search starts at the top again.
    var userScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) userScrolled = true }
    }
    LaunchedEffect(state.query) {
        userScrolled = false
        listState.scrollToItem(0)
    }
    val resultShape = listOf(
        state.artists.size, state.everything.artists.size, state.albums.size, state.everything.albums.size,
        state.playlists.size, state.tracks.size, state.everything.tracks.size,
    )
    LaunchedEffect(resultShape) { if (!userScrolled) listState.scrollToItem(0) }

    LaunchedEffect(Unit) { if (state.query.isBlank()) focusRequester.requestFocus() }
    val snackbar = LocalSnackbarHostState.current
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { m ->
            snackbar.showSnackbar(if (m.arg == null) context.getString(m.text) else context.getString(m.text, m.arg))
        }
    }

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
                KindChips(selected = state.kind, onSelect = viewModel::setKind)
                LibraryFilterRow(
                    filter = state.filter,
                    fields = state.filterFields,
                    onChange = viewModel::setFilter,
                    genres = genres,
                    years = years,
                    leading = { if (state.scopesAvailable) SourceChip(selected = state.scope, onSelect = viewModel::setScope) },
                )
                OfflineModeBanner()
                if (state.isSearchingRemote || state.everything.searching) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                }
            }
        },
    ) { padding ->
        when {
            !state.hasQuery -> RecentSearches(
                state = state,
                onPick = { viewModel.onQueryChange(it); viewModel.submit() },
                onRemove = viewModel::removeRecent,
                modifier = Modifier.padding(padding),
            )
            state.isEmpty && !state.isSearchingRemote && !state.everything.searching -> Column(Modifier.padding(padding)) {
                val everything = state.showsEverything
                EmptyState(
                    title = stringResource(R.string.browse_search_no_results),
                    body = stringResource(
                        when {
                            everything && state.everything.failed -> R.string.browse_search_everything_failed_empty
                            everything && state.everything.catalogDown -> R.string.browse_search_everything_offline
                            state.canSearchFurther && everything -> R.string.browse_search_try_everything
                            state.canSearchFurther -> R.string.browse_search_try_server
                            everything -> R.string.browse_search_no_results_everything
                            else -> R.string.browse_search_no_results_remote
                        },
                    ),
                    icon = if (state.remoteFailed || (everything && state.everything.failed)) Icons.Rounded.CloudOff else Icons.Rounded.SearchOff,
                    actionLabel = if (state.canSearchFurther) stringResource(searchFurtherLabel(state)) else null,
                    onAction = { viewModel.submit(); keyboard?.hide() },
                )
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = listContentPadding(top = 4.dp),
            ) {
                // Exact matches first, whatever they are (Spotify's top result), labelled with their type.
                if (!state.top.isEmpty) {
                    item(key = "h-top") { SectionHeader(title = stringResource(R.string.browse_search_top_results)) }
                    val top = state.top
                    items(top.artists, key = { "ta-" + it.id }, contentType = { "artist" }) { artist ->
                        MediaRow(
                            title = artist.name,
                            subtitle = stringResource(R.string.browse_search_type_artist),
                            artworkUrl = artist.artworkUrl,
                            onClick = { viewModel.rememberSearch(state.query); navigator.openArtist(artist.id) },
                            shape = CircleShape,
                            placeholder = Icons.Rounded.Person,
                        )
                    }
                    items(top.catalogArtists, key = { "tea-" + it.key }, contentType = { "everything-artist" }) { artist ->
                        CatalogArtistRow(artist, subtitle = stringResource(R.string.browse_search_type_artist), onClick = {
                            viewModel.rememberSearch(state.query)
                            artist.musicBrainzId?.let { navigator.openCatalogArtist(it, artist.name) }
                        })
                    }
                    items(top.albums, key = { "tal-" + it.id }, contentType = { "album" }) { album ->
                        MediaRow(
                            title = album.name,
                            subtitle = typed(R.string.browse_search_type_album, album.artistName),
                            artworkUrl = album.artworkUrl,
                            onClick = { viewModel.rememberSearch(state.query); navigator.openAlbum(album.id) },
                            onLongClick = { albumForOptions = album },
                            onMoreClick = { albumForOptions = album },
                            placeholder = Icons.Rounded.Album,
                        )
                    }
                    items(top.catalogAlbums, key = { "teal-" + it.key }, contentType = { "everything" }) { album ->
                        CatalogRow(
                            title = album.title,
                            subtitle = typed(R.string.browse_search_type_album, album.artist),
                            artworkUrl = album.artworkUrl,
                            availability = album.availability,
                            releaseGroupId = album.releaseGroupId,
                            busy = album.releaseGroupId in state.everything.requesting,
                            requestLabel = stringResource(R.string.browse_action_request),
                            onRequest = viewModel::request,
                            onClick = album.releaseGroupId?.let { rg -> { navigator.openCatalogAlbum(rg, album.title) } },
                        )
                    }
                    items(top.playlists, key = { "tp-" + it.id }, contentType = { "playlist" }) { playlist ->
                        MediaRow(
                            title = playlist.name,
                            subtitle = stringResource(R.string.browse_search_type_playlist),
                            artworkUrl = playlist.artworkUrl,
                            onClick = { viewModel.rememberSearch(state.query); navigator.openPlaylist(playlist.id) },
                            placeholder = Icons.AutoMirrored.Rounded.QueueMusic,
                        )
                    }
                    items(top.tracks, key = { "tt-" + it.id }, contentType = { "track" }) { track ->
                        TrackSwipeRow(track = track) {
                            TrackRow(
                                track = track,
                                onClick = { viewModel.playTrack(track) },
                                isCurrent = track.id == state.currentTrackId,
                                isPlaying = state.isPlaying,
                                subtitle = typed(R.string.browse_search_type_song, track.artistName),
                                isDownloaded = track.id in downloadedIds,
                                onLongClick = { trackForOptions = track },
                                onMoreClick = { trackForOptions = track },
                            )
                        }
                    }
                    items(top.catalogTracks, key = { "tet-" + it.key }, contentType = { "everything" }) { track ->
                        CatalogRow(
                            title = track.title,
                            subtitle = typed(R.string.browse_search_type_song, track.artist),
                            artworkUrl = null,
                            availability = track.availability,
                            releaseGroupId = track.albumReleaseGroupId,
                            busy = track.albumReleaseGroupId in state.everything.requesting,
                            requestLabel = stringResource(R.string.browse_action_request_album),
                            onRequest = viewModel::request,
                            onClick = track.albumReleaseGroupId?.let { rg -> { navigator.openCatalogAlbum(rg, track.albumTitle ?: track.title) } },
                        )
                    }
                }
                // Then by type: artists, albums, playlists, songs; yours first, best matches first.
                if (state.artists.isNotEmpty() || state.everything.artists.isNotEmpty()) {
                    item(key = "h-artists") { SectionHeader(title = stringResource(R.string.browse_tab_artists)) }
                    items(state.artists, key = { "a-" + it.id }, contentType = { "artist" }) { artist ->
                        ArtistRow(artist, onClick = { viewModel.rememberSearch(state.query); navigator.openArtist(artist.id) })
                    }
                    items(state.everything.artists, key = { "e-ar-" + it.key }, contentType = { "everything-artist" }) { artist ->
                        CatalogArtistRow(artist, onClick = {
                            viewModel.rememberSearch(state.query)
                            artist.musicBrainzId?.let { navigator.openCatalogArtist(it, artist.name) }
                        })
                    }
                }
                if (state.albums.isNotEmpty() || state.everything.albums.isNotEmpty()) {
                    item(key = "h-albums") { SectionHeader(title = stringResource(R.string.browse_tab_albums)) }
                    items(state.albums, key = { "al-" + it.id }, contentType = { "album" }) { album ->
                        AlbumRow(
                            album,
                            onClick = { viewModel.rememberSearch(state.query); navigator.openAlbum(album.id) },
                            onLongClick = { albumForOptions = album },
                            onMoreClick = { albumForOptions = album },
                            trailing = state.everything.partials[album.id]?.let { partial -> @Composable { PartialBadge(partial) } },
                        )
                    }
                    catalogAlbums(state.everything, onRequest = viewModel::request, onOpen = navigator::openCatalogAlbum)
                }
                if (state.playlists.isNotEmpty()) {
                    item(key = "h-playlists") { SectionHeader(title = stringResource(R.string.browse_tab_playlists)) }
                    items(state.playlists, key = { "p-" + it.id }, contentType = { "playlist" }) { playlist ->
                        PlaylistRow(playlist, onClick = { viewModel.rememberSearch(state.query); navigator.openPlaylist(playlist.id) })
                    }
                }
                if (state.tracks.isNotEmpty() || state.everything.tracks.isNotEmpty()) {
                    item(key = "h-tracks") { SectionHeader(title = stringResource(R.string.browse_tab_songs)) }
                    items(state.tracks, key = { "t-" + it.id }, contentType = { "track" }) { track ->
                        TrackSwipeRow(track = track) {
                            TrackRow(
                                track = track,
                                onClick = { viewModel.playTrack(track) },
                                isCurrent = track.id == state.currentTrackId,
                                isPlaying = state.isPlaying,
                                isDownloaded = track.id in downloadedIds,
                                onLongClick = { trackForOptions = track },
                                onMoreClick = { trackForOptions = track },
                            )
                        }
                    }
                    catalogTracks(state.everything, onRequest = viewModel::request, onOpen = navigator::openCatalogAlbum)
                }
                if (state.showsEverything) everythingStatus(state.everything)
                item(key = "remote") {
                    Column(Modifier.fillMaxWidth().padding(Dimens.screenPadding), horizontalAlignment = Alignment.CenterHorizontally) {
                        when {
                            state.scope == SearchScope.DOWNLOADED -> Unit
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
                            state.canSearchFurther -> FilledTonalButton(onClick = { viewModel.submit(); keyboard?.hide() }) {
                                Text(stringResource(searchFurtherLabel(state)))
                            }
                            state.remoteSearched -> Text(
                                stringResource(R.string.browse_search_includes_server),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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

/** "Album · Peach Pit": a top result's type, with who it's by when known. */
@Composable
private fun typed(type: Int, by: String?): String =
    listOfNotNull(stringResource(type), by?.takeIf { it.isNotBlank() }).joinToString(" · ")

/** Row 1 under the search field: which kinds of result to show. */
@Composable
private fun KindChips(selected: SearchKind, onSelect: (SearchKind) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Dimens.screenPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SearchKind.entries.forEach { kind ->
            FilterChip(
                selected = kind == selected,
                onClick = { onSelect(kind) },
                label = {
                    Text(
                        stringResource(
                            when (kind) {
                                SearchKind.ALL -> R.string.browse_search_kind_all
                                SearchKind.ARTISTS -> R.string.browse_tab_artists
                                SearchKind.ALBUMS -> R.string.browse_tab_albums
                                SearchKind.SONGS -> R.string.browse_tab_songs
                                SearchKind.PLAYLISTS -> R.string.browse_tab_playlists
                            },
                        ),
                    )
                },
            )
        }
    }
}

/** The first filter chip on a continuo server: where results come from (All / Library / Downloaded). */
@Composable
private fun SourceChip(selected: SearchScope, onSelect: (SearchScope) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected != SearchScope.ALL,
            onClick = { open = true },
            label = { Text(stringResource(R.string.browse_search_source, stringResource(scopeLabel(selected)))) },
            trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SearchScope.entries.forEach { scope ->
                DropdownMenuItem(
                    text = { Text(stringResource(scopeLabel(scope))) },
                    onClick = { open = false; onSelect(scope) },
                    trailingIcon = { if (scope == selected) RadioButton(selected = true, onClick = null) },
                )
            }
        }
    }
}

private fun scopeLabel(scope: SearchScope): Int = when (scope) {
    SearchScope.ALL -> R.string.browse_search_scope_all
    SearchScope.LIBRARY -> R.string.browse_search_scope_library
    SearchScope.DOWNLOADED -> R.string.browse_search_scope_downloaded
}

/** The search button runs the server search and, on a continuo server, the Everything tier too. */
private fun searchFurtherLabel(state: SearchUiState): Int =
    if (state.showsEverything) R.string.browse_action_search_everything else R.string.browse_action_search_server

/** Catalogue albums that aren't in the library, after the library's own albums. */
private fun LazyListScope.catalogAlbums(everything: EverythingUi, onRequest: (String) -> Unit, onOpen: (String, String) -> Unit) {
    items(everything.albums, key = { "e-al-" + it.key }, contentType = { "everything" }) { album ->
        CatalogRow(
            title = album.title,
            subtitle = listOfNotNull(album.artist, album.year?.toString()).joinToString(" · ").ifBlank { null },
            artworkUrl = album.artworkUrl,
            availability = album.availability,
            releaseGroupId = album.releaseGroupId,
            busy = album.releaseGroupId in everything.requesting,
            requestLabel = stringResource(R.string.browse_action_request),
            onRequest = onRequest,
            onClick = album.releaseGroupId?.let { rg -> { onOpen(rg, album.title) } },
        )
    }
}

/** Catalogue songs that aren't in the library; Request asks for the song's album. */
private fun LazyListScope.catalogTracks(everything: EverythingUi, onRequest: (String) -> Unit, onOpen: (String, String) -> Unit) {
    items(everything.tracks, key = { "e-t-" + it.key }, contentType = { "everything" }) { track ->
        CatalogRow(
            title = track.title,
            subtitle = listOfNotNull(track.artist, track.albumTitle).joinToString(" · ").ifBlank { null },
            artworkUrl = null,
            availability = track.availability,
            releaseGroupId = track.albumReleaseGroupId,
            busy = track.albumReleaseGroupId in everything.requesting,
            requestLabel = stringResource(R.string.browse_action_request_album),
            onRequest = onRequest,
            // A song opens its album: that's what can be requested.
            onClick = track.albumReleaseGroupId?.let { rg -> { onOpen(rg, track.albumTitle ?: track.title) } },
        )
    }
}

/** How the catalogue search is going, under the results: searching, unreachable or down. */
private fun LazyListScope.everythingStatus(everything: EverythingUi) {
    val status = when {
        everything.failed -> R.string.browse_search_everything_failed
        everything.catalogDown -> R.string.browse_search_everything_offline
        everything.searching -> R.string.browse_search_everything_searching
        else -> null
    }
    if (status != null) {
        item(key = "everything-status") {
            Text(
                stringResource(status),
                style = MaterialTheme.typography.bodyMedium,
                color = if (everything.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.screenPadding, vertical = 8.dp),
            )
        }
    }
}
