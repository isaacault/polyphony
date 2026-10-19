package app.polyphony.ui.browse.albumlist

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polyphony.R
import app.polyphony.core.domain.model.Album
import app.polyphony.core.ui.components.EmptyState
import app.polyphony.core.ui.components.LoadingPlaceholder
import app.polyphony.core.ui.components.PlaceholderStyle
import app.polyphony.navigation.AlbumListKind
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.browse.AlbumGrid
import app.polyphony.ui.common.AlbumOptionsSheet

@Composable
fun AlbumListScreen(viewModel: AlbumListViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val columns by viewModel.gridColumns.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    var albumForOptions by remember { mutableStateOf<Album?>(null) }
    val title = stringResource(
        when (viewModel.kind) {
            AlbumListKind.RECENTLY_ADDED -> R.string.browse_home_recently_added
            AlbumListKind.RECENTLY_PLAYED -> R.string.browse_home_recently_played
            AlbumListKind.MOST_PLAYED -> R.string.browse_home_most_played
            AlbumListKind.RANDOM -> R.string.browse_home_random_picks
            AlbumListKind.STARRED -> R.string.browse_home_favorites
        },
    )

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.browse_action_back))
                    }
                },
                actions = {
                    if (viewModel.kind == AlbumListKind.RANDOM) {
                        IconButton(onClick = viewModel::reshuffle) {
                            Icon(Icons.Rounded.Shuffle, contentDescription = stringResource(R.string.browse_action_reshuffle))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val list = albums
        when {
            list == null -> LoadingPlaceholder(PlaceholderStyle.GRID, modifier = Modifier.padding(padding))
            list.isEmpty() -> EmptyState(title = stringResource(R.string.browse_empty_albums), modifier = Modifier.padding(padding))
            else -> AlbumGrid(
                albums = list,
                columns = columns,
                onAlbum = { navigator.openAlbum(it.id) },
                onAlbumLong = { albumForOptions = it },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
    albumForOptions?.let { AlbumOptionsSheet(album = it, onDismiss = { albumForOptions = null }) }
}
