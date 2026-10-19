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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polyphony.R
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Track
import app.polyphony.core.ui.components.AlbumCard
import app.polyphony.core.ui.components.EmptyState
import app.polyphony.core.ui.components.GenreChip
import app.polyphony.core.ui.components.HorizontalCarousel
import app.polyphony.core.ui.components.LoadingPlaceholder
import app.polyphony.core.ui.components.PlaceholderStyle
import app.polyphony.core.ui.components.PlaylistCard
import app.polyphony.core.ui.components.SectionHeader
import app.polyphony.core.ui.components.SyncProgressBar
import app.polyphony.core.ui.components.TrackCard
import app.polyphony.core.ui.components.listContentPadding
import app.polyphony.core.ui.theme.Dimens
import app.polyphony.core.ui.util.formatBytes
import app.polyphony.navigation.AlbumListKind
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.common.AlbumOptionsSheet
import app.polyphony.ui.common.OfflineModeBanner
import app.polyphony.ui.common.OfflineModeIconButton
import app.polyphony.ui.common.TrackOptionsSheet
import java.time.LocalTime

@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    var showServerSwitcher by remember { mutableStateOf(false) }
    var trackForOptions by remember { mutableStateOf<Track?>(null) }
    var albumForOptions by remember { mutableStateOf<Album?>(null) }
    val greeting = rememberGreeting()
    val titleRecentlyAdded = stringResource(R.string.browse_home_recently_added)
    val titleMostPlayed = stringResource(R.string.browse_home_most_played)
    val titleFavorites = stringResource(R.string.browse_home_favorites)

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
                        item(key = "continue-header") { SectionHeader(title = stringResource(R.string.browse_home_continue_listening)) }
                        item(key = "continue") {
                            HorizontalCarousel(items = state.continueListening, key = { "t-" + it.id }) { track ->
                                TrackCard(
                                    track = track,
                                    onClick = { viewModel.playContinueListening(state.continueListening.indexOf(track)) },
                                    onLongClick = { trackForOptions = track },
                                )
                            }
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
                    if (state.genres.isNotEmpty()) {
                        item(key = "genres-header") {
                            SectionHeader(
                                title = stringResource(R.string.browse_home_genres),
                                onSeeAll = { navigator.openLibrary("genres") },
                                seeAllLabel = stringResource(R.string.browse_action_see_all),
                            )
                        }
                        item(key = "genres") {
                            HorizontalCarousel(items = state.genres.take(24), key = { "g-" + it.name }) { genre ->
                                GenreChip(name = genre.name, onClick = { navigator.openGenre(genre.name) })
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
    trackForOptions?.let { TrackOptionsSheet(track = it, onDismiss = { trackForOptions = null }) }
    albumForOptions?.let { AlbumOptionsSheet(album = it, onDismiss = { albumForOptions = null }) }
}

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
