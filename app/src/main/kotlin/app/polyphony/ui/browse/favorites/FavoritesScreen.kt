package app.polyphony.ui.browse.favorites

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
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.StarBorder
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polyphony.R
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Track
import app.polyphony.core.ui.components.EmptyState
import app.polyphony.core.ui.components.LoadingPlaceholder
import app.polyphony.core.ui.components.PlaceholderStyle
import app.polyphony.core.ui.components.PlayShuffleButtons
import app.polyphony.core.ui.components.TrackRow
import app.polyphony.core.ui.components.listContentPadding
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.browse.AlbumGrid
import app.polyphony.ui.browse.AlbumList
import app.polyphony.ui.browse.ArtistGrid
import app.polyphony.ui.common.TrackSwipeRow
import app.polyphony.ui.common.AlbumOptionsSheet
import app.polyphony.ui.common.TrackOptionsSheet
import kotlinx.coroutines.launch

@Composable
fun FavoritesScreen(viewModel: FavoritesViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val columns by viewModel.gridColumns.collectAsStateWithLifecycle()
    val grid by viewModel.albumsGrid.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val feedback by viewModel.feedback.collectAsStateWithLifecycle()
    // Ratings mode turns this screen into Top rated: the same lists, chosen by rating.
    val emptyTitle = if (feedback.usesRatings) stringResource(R.string.feedback_empty_top_rated, feedback.topRatedMinimum) else stringResource(R.string.browse_empty_favorites)
    val emptyBody = stringResource(if (feedback.usesRatings) R.string.feedback_empty_top_rated_hint else R.string.browse_empty_favorites_hint)
    val emptyIcon = if (feedback.usesRatings) Icons.Rounded.StarBorder else Icons.Rounded.FavoriteBorder
    val pagerState = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()
    var albumForOptions by remember { mutableStateOf<Album?>(null) }
    var trackForOptions by remember { mutableStateOf<Track?>(null) }
    val tabs = listOf(stringResource(R.string.browse_tab_songs), stringResource(R.string.browse_tab_albums), stringResource(R.string.browse_tab_artists))

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(if (feedback.usesRatings) R.string.feedback_top_rated else R.string.browse_home_favorites)) },
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
    ) { padding ->
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize().padding(padding), beyondViewportPageCount = 1) { page ->
            when (page) {
                0 -> {
                    val list = tracks
                    when {
                        list == null -> LoadingPlaceholder(PlaceholderStyle.LIST)
                        list.isEmpty() -> EmptyState(title = emptyTitle, body = emptyBody, icon = emptyIcon)
                        else -> LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = listContentPadding(top = 4.dp)) {
                            item(key = "header") {
                                PlayShuffleButtons(
                                    onPlay = { viewModel.playAll(false) },
                                    onShuffle = { viewModel.playAll(true) },
                                    playLabel = stringResource(R.string.browse_action_play_all),
                                    shuffleLabel = stringResource(R.string.browse_action_shuffle),
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                            items(list, key = { it.id }, contentType = { "track" }) { track ->
                                TrackSwipeRow(track = track) {
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
                1 -> {
                    val list = albums
                    when {
                        list == null -> LoadingPlaceholder(PlaceholderStyle.GRID)
                        list.isEmpty() -> EmptyState(title = emptyTitle, body = emptyBody, icon = emptyIcon)
                        grid -> AlbumGrid(list, columns, onAlbum = { navigator.openAlbum(it.id) }, onAlbumLong = { albumForOptions = it })
                        else -> AlbumList(list, onAlbum = { navigator.openAlbum(it.id) }, onAlbumLong = { albumForOptions = it })
                    }
                }
                else -> {
                    val list = artists
                    when {
                        list == null -> LoadingPlaceholder(PlaceholderStyle.GRID)
                        list.isEmpty() -> EmptyState(title = emptyTitle, body = emptyBody, icon = emptyIcon)
                        else -> ArtistGrid(list, columns, onArtist = { navigator.openArtist(it.id) })
                    }
                }
            }
        }
    }
    albumForOptions?.let { AlbumOptionsSheet(album = it, onDismiss = { albumForOptions = null }) }
    trackForOptions?.let { TrackOptionsSheet(track = it, onDismiss = { trackForOptions = null }) }
}
