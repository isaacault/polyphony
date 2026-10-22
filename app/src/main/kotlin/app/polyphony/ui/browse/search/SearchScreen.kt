package app.polyphony.ui.browse.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polyphony.R
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.Track
import app.polyphony.core.ui.components.AlbumRow
import app.polyphony.core.ui.components.ArtworkImage
import app.polyphony.core.ui.components.QualityBadge
import app.polyphony.core.ui.components.ArtistRow
import app.polyphony.core.ui.components.EmptyState
import app.polyphony.core.ui.components.PlaylistRow
import app.polyphony.core.ui.components.SectionHeader
import app.polyphony.core.ui.components.TrackRow
import app.polyphony.core.ui.components.listContentPadding
import app.polyphony.core.ui.theme.Dimens
import app.polyphony.navigation.LocalNavigator
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
                // Each section lists what you have first, then catalogue items (dimmed, with Request).
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
                    catalogAlbums(state.everything, onRequest = viewModel::request)
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
                                onClick = { viewModel.playTrack(state.tracks.indexOf(track)) },
                                isCurrent = track.id == state.currentTrackId,
                                isPlaying = state.isPlaying,
                                isDownloaded = track.id in downloadedIds,
                                onLongClick = { trackForOptions = track },
                                onMoreClick = { trackForOptions = track },
                            )
                        }
                    }
                    catalogTracks(state.everything, onRequest = viewModel::request)
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
private fun LazyListScope.catalogAlbums(everything: EverythingUi, onRequest: (String) -> Unit) {
    items(everything.albums, key = { "e-al-" + it.key }, contentType = { "everything" }) { album ->
        EverythingRow(
            title = album.title,
            subtitle = listOfNotNull(album.artist, album.year?.toString()).joinToString(" · ").ifBlank { null },
            artworkUrl = album.artworkUrl,
            availability = album.availability,
            releaseGroupId = album.releaseGroupId,
            busy = album.releaseGroupId in everything.requesting,
            requestLabel = stringResource(R.string.browse_action_request),
            onRequest = onRequest,
        )
    }
}

/** Catalogue songs that aren't in the library; Request asks for the song's album. */
private fun LazyListScope.catalogTracks(everything: EverythingUi, onRequest: (String) -> Unit) {
    items(everything.tracks, key = { "e-t-" + it.key }, contentType = { "everything" }) { track ->
        EverythingRow(
            title = track.title,
            subtitle = listOfNotNull(track.artist, track.albumTitle).joinToString(" · ").ifBlank { null },
            artworkUrl = null,
            availability = track.availability,
            releaseGroupId = track.albumReleaseGroupId,
            busy = track.albumReleaseGroupId in everything.requesting,
            requestLabel = stringResource(R.string.browse_action_request_album),
            onRequest = onRequest,
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

/** On a library album the catalogue only partly matches: how much of the release you have. */
@Composable
private fun PartialBadge(partial: Availability.Partial) {
    QualityBadge(label = stringResource(R.string.browse_availability_partial, partial.have, partial.expected))
}

@Composable
private fun EverythingRow(
    title: String,
    subtitle: String?,
    artworkUrl: String?,
    availability: Availability,
    releaseGroupId: String?,
    busy: Boolean,
    requestLabel: String,
    onRequest: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = Dimens.screenPadding, end = Dimens.screenPadding, top = 4.dp, bottom = 4.dp)
            .heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Dimmed: not in your library. The Request button keeps full contrast.
        ArtworkImage(url = artworkUrl, contentDescription = null, modifier = Modifier.size(Dimens.listArtwork).alpha(CATALOG_ALPHA))
        Spacer(Modifier.width(Dimens.itemSpacing))
        Column(Modifier.weight(1f).alpha(CATALOG_ALPHA)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (EverythingSection.canRequest(availability, releaseGroupId) && releaseGroupId != null) {
            FilledTonalButton(
                onClick = { onRequest(releaseGroupId) },
                enabled = !busy,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) { Text(requestLabel, style = MaterialTheme.typography.labelLarge) }
        } else {
            availabilityLabel(availability)?.let { QualityBadge(label = stringResource(it)) }
        }
    }
}

private const val CATALOG_ALPHA = 0.6f

/** The tonal status chip for a hit that's already under way. */
private fun availabilityLabel(availability: Availability): Int? = when (availability) {
    is Availability.Requested -> when (availability.state) {
        "searching" -> if (availability.stillLooking) R.string.browse_availability_still_looking else R.string.browse_availability_searching
        "downloading" -> R.string.browse_availability_downloading
        "importing", "scanning" -> R.string.browse_availability_importing
        "needs_attention" -> R.string.browse_availability_needs_attention
        else -> R.string.browse_availability_requested
    }
    else -> null
}
