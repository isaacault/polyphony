package app.polyphony.ui.browse.genre

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polyphony.R
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Track
import app.polyphony.core.ui.components.EmptyState
import app.polyphony.core.ui.components.LoadingPlaceholder
import app.polyphony.core.ui.components.LocalContentBottomPadding
import app.polyphony.core.ui.components.PlaceholderStyle
import app.polyphony.core.ui.components.TrackRow
import app.polyphony.core.ui.components.listContentPadding
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.browse.AlbumGrid
import app.polyphony.ui.browse.AlbumList
import app.polyphony.ui.common.AlbumOptionsSheet
import app.polyphony.ui.common.TrackOptionsSheet
import kotlinx.coroutines.launch

@Composable
fun GenreScreen(viewModel: GenreViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val columns by viewModel.gridColumns.collectAsStateWithLifecycle()
    val grid by viewModel.albumsGrid.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    var albumForOptions by remember { mutableStateOf<Album?>(null) }
    var trackForOptions by remember { mutableStateOf<Track?>(null) }
    val tabs = listOf(stringResource(R.string.browse_tab_albums), stringResource(R.string.browse_tab_songs))

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(viewModel.genre, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.back() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.browse_action_back))
                        }
                    },
                )
                PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                    tabs.forEachIndexed { index, label ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = { Text(label) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.browse_action_shuffle)) },
                icon = { Icon(Icons.Rounded.Shuffle, contentDescription = null) },
                onClick = viewModel::shuffle,
                modifier = Modifier.padding(bottom = LocalContentBottomPadding.current),
            )
        },
    ) { padding ->
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize().padding(padding), beyondViewportPageCount = 1) { page ->
            if (page == 0) {
                val list = albums
                when {
                    list == null -> LoadingPlaceholder(PlaceholderStyle.GRID)
                    list.isEmpty() -> EmptyState(title = stringResource(R.string.browse_empty_albums), icon = Icons.Rounded.Category)
                    grid -> AlbumGrid(
                        albums = list,
                        columns = columns,
                        onAlbum = { navigator.openAlbum(it.id) },
                        onAlbumLong = { albumForOptions = it },
                        contentPadding = listContentPadding(top = 8.dp, horizontal = 12.dp, extraBottom = 96.dp),
                    )
                    else -> AlbumList(
                        albums = list,
                        onAlbum = { navigator.openAlbum(it.id) },
                        onAlbumLong = { albumForOptions = it },
                        contentPadding = listContentPadding(top = 8.dp, extraBottom = 96.dp),
                    )
                }
            } else {
                val list = tracks
                when {
                    list == null -> LoadingPlaceholder(PlaceholderStyle.LIST)
                    list.isEmpty() -> EmptyState(title = stringResource(R.string.browse_empty_songs), icon = Icons.Rounded.Category)
                    else -> LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = listContentPadding(top = 4.dp, extraBottom = 96.dp)) {
                        items(list, key = { it.id }, contentType = { "track" }) { track ->
                            TrackRow(
                                track = track,
                                onClick = { viewModel.playTrack(list.indexOf(track)) },
                                isCurrent = track.id == playback.currentTrack?.id,
                                isPlaying = playback.isPlaying,
                                onLongClick = { trackForOptions = track },
                                onMoreClick = { trackForOptions = track },
                            )
                        }
                    }
                }
            }
        }
    }
    albumForOptions?.let { AlbumOptionsSheet(album = it, onDismiss = { albumForOptions = null }) }
    trackForOptions?.let { TrackOptionsSheet(track = it, onDismiss = { trackForOptions = null }) }
}
