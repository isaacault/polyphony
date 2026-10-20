package app.polyphony.ui.browse.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.browse_nav_library)) },
                    actions = {
                        IconButton(onClick = viewModel::shuffleLibrary) {
                            Icon(Icons.Rounded.Shuffle, contentDescription = stringResource(R.string.browse_action_shuffle_all))
                        }
                        IconButton(onClick = { navigator.openSearch() }) {
                            Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.browse_nav_search))
                        }
                        OfflineModeIconButton()
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.browse_action_more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.dl_title)) },
                                    leadingIcon = { Icon(Icons.Rounded.DownloadForOffline, contentDescription = null) },
                                    onClick = { menuOpen = false; navigator.openDownloads() },
                                )
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
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = { Text(stringResource(tab.labelRes())) },
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
    LibraryTab.ARTISTS -> R.string.browse_tab_artists
    LibraryTab.ALBUMS -> R.string.browse_tab_albums
    LibraryTab.SONGS -> R.string.browse_tab_songs
    LibraryTab.PLAYLISTS -> R.string.browse_tab_playlists
    LibraryTab.GENRES -> R.string.browse_tab_genres
}
