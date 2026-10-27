package app.polyphony.ui.browse.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polyphony.R
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.CatalogTrack
import app.polyphony.core.domain.model.PlayContext
import app.polyphony.core.domain.model.RecentContext
import app.polyphony.core.ui.components.AlbumCard
import app.polyphony.core.ui.components.EmptyState
import app.polyphony.core.ui.components.HorizontalCarousel
import app.polyphony.core.ui.components.LoadingPlaceholder
import app.polyphony.core.ui.components.PlaceholderStyle
import app.polyphony.core.ui.components.PlaylistCard
import app.polyphony.core.ui.components.SectionHeader
import app.polyphony.core.ui.components.SyncProgressBar
import app.polyphony.core.ui.components.listContentPadding
import app.polyphony.core.ui.theme.Dimens
import app.polyphony.core.ui.util.formatBytes
import app.polyphony.navigation.AlbumListKind
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.browse.catalog.CatalogAlbumCard
import app.polyphony.ui.browse.catalog.CatalogRow
import app.polyphony.ui.browse.catalog.Discography
import app.polyphony.ui.browse.catalog.requestTrackLabel
import app.polyphony.ui.common.AlbumOptionsSheet
import app.polyphony.ui.common.CatalogSongSheet
import app.polyphony.ui.common.CatalogSwipeRow
import app.polyphony.ui.common.OfflineModeBanner
import app.polyphony.ui.common.OfflineModeIconButton
import app.polyphony.ui.common.PlayNowViewModel
import app.polyphony.ui.shell.LocalSnackbarHostState
import java.time.LocalTime

@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    var showServerSwitcher by remember { mutableStateOf(false) }
    var contextForOptions by remember { mutableStateOf<RecentContext?>(null) }
    val openContext: (RecentContext) -> Unit = { entry ->
        val c = entry.context
        when (c.kind) {
            PlayContext.Kind.ALBUM -> navigator.openAlbum(c.id)
            PlayContext.Kind.ARTIST -> navigator.openArtist(c.id)
            PlayContext.Kind.PLAYLIST -> navigator.openPlaylist(c.id)
            PlayContext.Kind.GENRE -> navigator.openGenre(c.id)
            PlayContext.Kind.FAVORITES -> navigator.openFavorites()
        }
    }
    var albumForOptions by remember { mutableStateOf<Album?>(null) }
    val greeting = rememberGreeting()
    val titleRecentlyAdded = stringResource(R.string.browse_home_recently_added)
    val titleMostPlayed = stringResource(R.string.browse_home_most_played)
    val feedback by viewModel.feedback.collectAsStateWithLifecycle()
    val discovery by viewModel.discovery.collectAsStateWithLifecycle()
    val playNow = hiltViewModel<PlayNowViewModel>().playNow
    val canPlayNow by playNow.available.collectAsStateWithLifecycle()
    var songForOptions by remember { mutableStateOf<CatalogTrack?>(null) }
    var showAllForYou by remember { mutableStateOf(false) }
    val snackbar = LocalSnackbarHostState.current
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { m ->
            snackbar.showSnackbar(if (m.arg == null) context.getString(m.text) else context.getString(m.text, m.arg))
        }
    }
    val titleFavorites = stringResource(if (feedback.usesRatings) R.string.feedback_top_rated else R.string.browse_home_favorites)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(greeting, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    actions = {
                        OfflineModeIconButton()
                        IconButton(onClick = { navigator.openSearch() }) {
                            Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.browse_nav_search))
                        }
                        IconButton(onClick = { navigator.openSettings() }) {
                            Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.browse_nav_settings))
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
                OfflineModeBanner()
                SyncProgressBar(state.sync)
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                !state.isLoaded -> LazyColumn(contentPadding = listContentPadding(top = 8.dp)) {
                    item { LoadingPlaceholder(PlaceholderStyle.CAROUSEL) }
                    item { Spacer(Modifier.height(Dimens.sectionSpacing)) }
                    item { LoadingPlaceholder(PlaceholderStyle.CAROUSEL) }
                }
                state.isLibraryEmpty -> LazyColumn(contentPadding = listContentPadding(top = 8.dp)) {
                    item { ServerChipRow(state, onClick = { showServerSwitcher = true }) }
                    item {
                        EmptyState(
                            title = stringResource(R.string.browse_home_empty_title),
                            body = stringResource(
                                if (state.sync.isRunning) R.string.browse_home_empty_syncing else R.string.browse_home_empty_body,
                            ),
                            actionLabel = if (state.sync.isRunning) null else stringResource(R.string.browse_action_sync_now),
                            onAction = { viewModel.refresh(full = true) },
                        )
                    }
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = listContentPadding(top = 4.dp),
                ) {
                    item(key = "server") { ServerChipRow(state, onClick = { showServerSwitcher = true }) }

                    if (state.continueListening.isNotEmpty()) {
                        // Quick picks sit right under the greeting, like Spotify: no header, wide tiles.
                        item(key = "continue") {
                            ContinueListeningGrid(
                                entries = state.continueListening,
                                onClick = openContext,
                                onLongClick = { contextForOptions = it },
                                modifier = Modifier.padding(top = Dimens.tightSpacing, bottom = Dimens.sectionSpacing),
                            )
                        }
                    }

                    if (state.downloadedCount > 0) {
                        item(key = "downloaded-header") {
                            SectionHeader(
                                title = stringResource(R.string.dl_home_section),
                                subtitle = stringResource(R.string.dl_home_summary, state.downloadedCount, formatBytes(state.downloadedBytes)),
                                onSeeAll = navigator::openDownloads,
                                seeAllLabel = stringResource(R.string.dl_see_all),
                            )
                        }
                    }

                    albumShelf(
                        key = "recent",
                        title = titleRecentlyAdded,
                        albums = state.recentlyAdded,
                        onSeeAll = { navigator.openAlbumList(AlbumListKind.RECENTLY_ADDED) },
                        onAlbum = navigator::openAlbum,
                        onAlbumLong = { albumForOptions = it },
                    )
                    // Discovery (continuo): albums you don't have yet, and songs you might like.
                    if (discovery.newReleases.isNotEmpty()) {
                        item(key = "new-header") { SectionHeader(title = stringResource(R.string.browse_home_new_from_artists)) }
                        item(key = "new") {
                            HorizontalCarousel(items = discovery.newReleases, key = { "n-" + it.key }) { album ->
                                val libraryId = Discography.libraryId(album.availability)
                                CatalogAlbumCard(
                                    album = album,
                                    busy = album.releaseGroupId in discovery.requesting,
                                    onRequest = viewModel::request,
                                    onClick = {
                                        when {
                                            libraryId != null -> navigator.openAlbum(libraryId)
                                            album.releaseGroupId != null -> navigator.openCatalogAlbum(album.releaseGroupId!!, album.title)
                                        }
                                    },
                                    modifier = Modifier.width(Dimens.carouselArtwork),
                                )
                            }
                        }
                    }
                    if (discovery.forYou.isNotEmpty()) {
                        val visible = if (showAllForYou) discovery.forYou else discovery.forYou.take(FOR_YOU_SHOWN)
                        item(key = "foryou-header") { SectionHeader(title = stringResource(R.string.browse_home_for_you)) }
                        items(visible, key = { "fy-" + it.key }, contentType = { "catalog-track" }) { track ->
                            val inLibrary = (track.availability as? Availability.InLibrary)?.libraryAlbumId
                            CatalogSwipeRow(track) {
                                CatalogRow(
                                    title = track.title,
                                    subtitle = track.artist,
                                    artworkUrl = null,
                                    availability = track.availability,
                                    releaseGroupId = track.albumReleaseGroupId,
                                    busy = track.requestKeys.any { it in discovery.requesting },
                                    requestLabel = stringResource(requestTrackLabel(track)),
                                    onRequest = { viewModel.requestTrack(track) },
                                    onClick = when {
                                        inLibrary != null -> ({ navigator.openAlbum(inLibrary) })
                                        canPlayNow && track.recordingId != null -> ({ playNow.play(track) })
                                        else -> null
                                    },
                                    onLongClick = if (inLibrary == null) ({ songForOptions = track }) else null,
                                )
                            }
                        }
                        if (discovery.forYou.size > FOR_YOU_SHOWN) {
                            item(key = "foryou-more") {
                                TextButton(onClick = { showAllForYou = !showAllForYou }, modifier = Modifier.padding(start = 8.dp)) {
                                    Text(stringResource(if (showAllForYou) R.string.browse_action_show_less else R.string.browse_action_show_more))
                                }
                            }
                        }
                    }
                    albumShelf(
                        key = "most",
                        title = titleMostPlayed,
                        albums = state.mostPlayed,
                        onSeeAll = { navigator.openAlbumList(AlbumListKind.MOST_PLAYED) },
                        onAlbum = navigator::openAlbum,
                        onAlbumLong = { albumForOptions = it },
                    )
                    if (state.randomPicks.isNotEmpty()) {
                        item(key = "random-header") {
                            SectionHeader(
                                title = stringResource(R.string.browse_home_random_picks),
                                action = {
                                    IconButton(onClick = viewModel::reshuffleRandom) {
                                        Icon(Icons.Rounded.Shuffle, contentDescription = stringResource(R.string.browse_action_reshuffle))
                                    }
                                },
                            )
                        }
                        item(key = "random") {
                            HorizontalCarousel(items = state.randomPicks, key = { "r-" + it.id }) { album ->
                                AlbumCard(album, onClick = { navigator.openAlbum(album.id) }, onLongClick = { albumForOptions = album }, width = Dimens.carouselArtwork)
                            }
                        }
                    }
                    albumShelf(
                        key = "fav",
                        title = titleFavorites,
                        albums = state.favorites,
                        onSeeAll = { navigator.openFavorites() },
                        onAlbum = navigator::openAlbum,
                        onAlbumLong = { albumForOptions = it },
                    )
                    if (state.playlists.isNotEmpty()) {
                        item(key = "playlists-header") {
                            SectionHeader(
                                title = stringResource(R.string.browse_home_playlists),
                                onSeeAll = { navigator.openLibrary("playlists") },
                                seeAllLabel = stringResource(R.string.browse_action_see_all),
                            )
                        }
                        item(key = "playlists") {
                            HorizontalCarousel(items = state.playlists.take(15), key = { "p-" + it.id }) { playlist ->
                                PlaylistCard(playlist, onClick = { navigator.openPlaylist(playlist.id) }, width = Dimens.carouselArtwork)
                            }
                        }
                    }
                    item(key = "bottom") { Spacer(Modifier.height(Dimens.sectionSpacing)) }
                }
            }
        }
    }

    if (showServerSwitcher) {
        ModalBottomSheet(onDismissRequest = { showServerSwitcher = false }) {
            Text(
                stringResource(R.string.browse_title_servers),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.navigationBarsPadding()) {
                items(state.servers, key = { it.id }) { server ->
                    val active = server.id == state.activeServer?.id
                    ListItem(
                        headlineContent = { Text(server.name) },
                        supportingContent = { Text("${server.type.displayName} · ${server.displayHost}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(Icons.Rounded.Dns, contentDescription = null) },
                        trailingContent = { if (active) Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {
                            if (!active) viewModel.switchServer(server.id)
                            showServerSwitcher = false
                        },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.browse_action_manage_servers)) },
                        leadingContent = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { showServerSwitcher = false; navigator.openServersSettings() },
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
    contextForOptions?.let { entry ->
        ContinueListeningSheet(
            entry = entry,
            onDismiss = { contextForOptions = null },
            onResume = { viewModel.resume(entry) },
            onPlayFromStart = { viewModel.playFromStart(entry) },
            onRemove = { viewModel.removeFromHistory(entry) },
        )
    }
    albumForOptions?.let { AlbumOptionsSheet(album = it, onDismiss = { albumForOptions = null }) }
    songForOptions?.let { song ->
        CatalogSongSheet(
            track = song,
            onOpenAlbum = song.albumReleaseGroupId?.let { rg -> { navigator.openCatalogAlbum(rg, song.albumTitle ?: song.title) } },
            onDismiss = { songForOptions = null },
        )
    }
}

/** For you's songs shown before Show more. */
private const val FOR_YOU_SHOWN = 5

@Composable
private fun ServerChipRow(state: HomeUiState, onClick: () -> Unit) {
    val server = state.activeServer ?: return
    Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.screenPadding, vertical = 4.dp)) {
        AssistChip(
            onClick = onClick,
            label = { Text(server.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Rounded.Dns, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
            shape = MaterialTheme.shapes.small,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.albumShelf(
    key: String,
    title: String,
    albums: List<Album>,
    onSeeAll: () -> Unit,
    onAlbum: (String) -> Unit,
    onAlbumLong: (Album) -> Unit,
) {
    if (albums.isEmpty()) return
    item(key = "$key-header") { SectionHeader(title = title, onSeeAll = onSeeAll) }
    item(key = key) {
        HorizontalCarousel(items = albums, key = { "$key-" + it.id }) { album ->
            AlbumCard(album, onClick = { onAlbum(album.id) }, onLongClick = { onAlbumLong(album) }, width = Dimens.carouselArtwork)
        }
    }
}

@Composable
private fun rememberGreeting(): String {
    val hour = remember { LocalTime.now().hour }
    return stringResource(
        when (hour) {
            in 5..11 -> R.string.browse_greeting_morning
            in 12..17 -> R.string.browse_greeting_afternoon
            else -> R.string.browse_greeting_evening
        },
    )
}
