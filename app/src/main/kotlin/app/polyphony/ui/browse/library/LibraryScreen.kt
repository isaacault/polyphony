package app.polyphony.ui.browse.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polyphony.R
import app.polyphony.core.ui.components.SyncProgressBar
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.common.OfflineModeBanner
import app.polyphony.ui.common.rememberContinuoLinked
import app.polyphony.ui.common.OfflineModeIconButton
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(
    initialTab: String?,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val navigator = LocalNavigator.current
    val start by viewModel.libraryStart.collectAsStateWithLifecycle()
    // Settings arrive within a frame; drawing nothing until then beats starting on the wrong tab.
    val (tabs, defaultTab) = start ?: return
    val pagerState = key(tabs, defaultTab) {
        val first = LibraryTab.fromKey(initialTab)?.takeIf { it in tabs } ?: defaultTab
        rememberPagerState(initialPage = tabs.indexOf(first).coerceAtLeast(0)) { tabs.size }
    }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val sync by viewModel.syncState.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    val continuoLinked by rememberContinuoLinked()
    val artistsGrid by viewModel.artistsGrid.collectAsStateWithLifecycle()
    val albumsGrid by viewModel.albumsGrid.collectAsStateWithLifecycle()
    val playlistsGrid by viewModel.playlistsGrid.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.browse_nav_library)) },
                    // Search has its tab (with a Library scope); grid or list is in the menu.
                    actions = {
                        OfflineModeIconButton()
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.browse_action_more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                val current = tabs.getOrNull(pagerState.currentPage)
                                val isGrid = when (current) {
                                    LibraryTab.ARTISTS -> artistsGrid
                                    LibraryTab.ALBUMS -> albumsGrid
                                    LibraryTab.PLAYLISTS -> playlistsGrid
                                    else -> null
                                }
                                if (isGrid != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(if (isGrid) R.string.browse_action_show_list else R.string.browse_action_show_grid)) },
                                        leadingIcon = {
                                            Icon(if (isGrid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView, contentDescription = null)
                                        },
                                        onClick = {
                                            menuOpen = false
                                            when (current) {
                                                LibraryTab.ARTISTS -> viewModel.setArtistsGrid(!isGrid)
                                                LibraryTab.ALBUMS -> viewModel.setAlbumsGrid(!isGrid)
                                                else -> viewModel.setPlaylistsGrid(!isGrid)
                                            }
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.dl_title)) },
                                    leadingIcon = { Icon(Icons.Rounded.DownloadForOffline, contentDescription = null) },
                                    onClick = { menuOpen = false; navigator.openDownloads() },
                                )
                                if (continuoLinked) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.requests_title)) },
                                        leadingIcon = { Icon(Icons.Rounded.LibraryAdd, contentDescription = null) },
                                        onClick = { menuOpen = false; navigator.openRequests() },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.browse_nav_settings)) },
                                    leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                                    onClick = { menuOpen = false; navigator.openSettings() },
                                )
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
                OfflineModeBanner()
                PrimaryScrollableTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    edgePadding = 8.dp,
                    divider = {},
                ) {
                    tabs.forEachIndexed { index, tab ->
                        // Lower than Material's 48 dp tabs: the bar under the title stays slim.
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = { Text(stringResource(tab.labelRes()), style = MaterialTheme.typography.labelLarge) },
                            modifier = Modifier.height(38.dp),
                        )
                    }
                }
                SyncProgressBar(sync)
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            beyondViewportPageCount = 1,
            key = { tabs[it].key },
        ) { page ->
            when (tabs[page]) {
                LibraryTab.EXPLORE -> ExploreTab()
                LibraryTab.ARTISTS -> ArtistsTab(viewModel)
                LibraryTab.ALBUMS -> AlbumsTab(viewModel)
                LibraryTab.SONGS -> SongsTab(viewModel)
                LibraryTab.PLAYLISTS -> PlaylistsTab(viewModel)
                LibraryTab.GENRES -> GenresTab(viewModel)
            }
        }
    }
}

private fun LibraryTab.labelRes(): Int = when (this) {
    LibraryTab.EXPLORE -> R.string.browse_tab_explore
    LibraryTab.ARTISTS -> R.string.browse_tab_artists
    LibraryTab.ALBUMS -> R.string.browse_tab_albums
    LibraryTab.SONGS -> R.string.browse_tab_songs
    LibraryTab.PLAYLISTS -> R.string.browse_tab_playlists
    LibraryTab.GENRES -> R.string.browse_tab_genres
}
