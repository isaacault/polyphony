package app.polyphony.ui.browse.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polyphony.R
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.AlbumSort
import app.polyphony.core.domain.model.ArtistSort
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.model.TrackSort
import app.polyphony.core.ui.components.EmptyState
import app.polyphony.core.ui.components.FastScroller
import app.polyphony.core.ui.components.GenreRow
import app.polyphony.core.ui.components.GridListToggle
import app.polyphony.core.ui.components.LoadingPlaceholder
import app.polyphony.core.ui.components.LocalContentBottomPadding
import app.polyphony.core.ui.components.PlaceholderStyle
import app.polyphony.core.ui.components.PlayShuffleButtons
import app.polyphony.core.ui.components.PlaylistCard
import app.polyphony.core.ui.components.PlaylistRow
import app.polyphony.core.ui.components.SortMenu
import app.polyphony.core.ui.components.SortOption
import app.polyphony.core.ui.components.TrackRow
import app.polyphony.core.ui.components.listContentPadding
import app.polyphony.core.ui.util.availableLetters
import app.polyphony.core.ui.util.letterIndex
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.browse.AlbumGrid
import app.polyphony.ui.browse.AlbumList
import app.polyphony.ui.browse.ArtistGrid
import app.polyphony.ui.browse.ArtistList
import app.polyphony.ui.browse.ListControlsRow
import app.polyphony.ui.browse.ScrollerHost
import app.polyphony.ui.browse.gridCells
import app.polyphony.ui.common.AlbumOptionsSheet
import app.polyphony.ui.common.RenameDialog
import app.polyphony.ui.common.TrackOptionsSheet
import app.polyphony.ui.common.rememberDownloadedIds
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- Artists

@Composable
internal fun ArtistsTab(vm: LibraryViewModel) {
    val navigator = LocalNavigator.current
    val artists by vm.artists.collectAsStateWithLifecycle()
    val sort by vm.artistSort.collectAsStateWithLifecycle()
    val grid by vm.artistsGrid.collectAsStateWithLifecycle()
    val columns by vm.gridColumns.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val sortOptions = remember {
        persistentListOf(
            SortOption(ArtistSort.NAME, "Name"),
            SortOption(ArtistSort.ALBUM_COUNT, "Albums"),
            SortOption(ArtistSort.RECENTLY_ADDED, "Recently added"),
            SortOption(ArtistSort.STARRED, "Favourites"),
        )
    }
    Column(Modifier.fillMaxSize()) {
        ListControlsRow(
            countLabel = artists?.let { pluralStringResource(R.plurals.browse_count_artists, it.size, it.size) },
            start = { SortMenu(sortOptions, sort, ascending = true, onChange = { s, _ -> vm.setArtistSort(s) }, showDirection = false) },
            end = { GridListToggle(isGrid = grid, onToggle = vm::setArtistsGrid) },
        )
        val list = artists
        when {
            list == null -> LoadingPlaceholder(if (grid) PlaceholderStyle.GRID else PlaceholderStyle.LIST)
            list.isEmpty() -> EmptyState(title = stringResource(R.string.browse_empty_artists), body = stringResource(R.string.browse_empty_library_hint))
            else -> ScrollerHost(
                scroller = if (sort == ArtistSort.NAME) {
                    {
                        FastScroller(
                            availableLetters = remember(list) { availableLetters(list) { it.sortName ?: it.name } },
                            onLetterSelected = { letter ->
                                val index = letterIndex(list, letter) { it.sortName ?: it.name }
                                scope.launch { if (grid) gridState.scrollToItem(index) else listState.scrollToItem(index) }
                            },
                        )
                    }
                } else null,
            ) {
                if (grid) ArtistGrid(list, columns, onArtist = { navigator.openArtist(it.id) }, state = gridState)
                else ArtistList(list, onArtist = { navigator.openArtist(it.id) }, state = listState)
            }
        }
    }
}

// ---------------------------------------------------------------- Albums

@Composable
internal fun AlbumsTab(vm: LibraryViewModel) {
    val navigator = LocalNavigator.current
    val albums by vm.albums.collectAsStateWithLifecycle()
    val sort by vm.albumSort.collectAsStateWithLifecycle()
    val ascending by vm.albumAscending.collectAsStateWithLifecycle()
    val grid by vm.albumsGrid.collectAsStateWithLifecycle()
    val columns by vm.gridColumns.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var albumForOptions by remember { mutableStateOf<Album?>(null) }
    val sortOptions = remember {
        persistentListOf(
            SortOption(AlbumSort.NAME, "Name"),
            SortOption(AlbumSort.ARTIST, "Artist"),
            SortOption(AlbumSort.YEAR, "Year"),
            SortOption(AlbumSort.RECENTLY_ADDED, "Recently added"),
            SortOption(AlbumSort.RECENTLY_PLAYED, "Recently played"),
            SortOption(AlbumSort.MOST_PLAYED, "Most played"),
            SortOption(AlbumSort.RATING, "Rating"),
            SortOption(AlbumSort.DURATION, "Duration"),
        )
    }
    Column(Modifier.fillMaxSize()) {
        ListControlsRow(
            countLabel = albums?.let { pluralStringResource(R.plurals.browse_count_albums, it.size, it.size) },
            start = { SortMenu(sortOptions, sort, ascending, onChange = vm::setAlbumSort) },
            end = { GridListToggle(isGrid = grid, onToggle = vm::setAlbumsGrid) },
        )
        val list = albums
        when {
            list == null -> LoadingPlaceholder(if (grid) PlaceholderStyle.GRID else PlaceholderStyle.LIST)
            list.isEmpty() -> EmptyState(title = stringResource(R.string.browse_empty_albums), body = stringResource(R.string.browse_empty_library_hint))
            else -> {
                val nameOf: (Album) -> String? = when (sort) {
                    AlbumSort.ARTIST -> { a -> a.artistName }
                    else -> { a -> a.sortName ?: a.name }
                }
                ScrollerHost(
                    scroller = if ((sort == AlbumSort.NAME || sort == AlbumSort.ARTIST) && ascending) {
                        {
                            FastScroller(
                                availableLetters = remember(list, sort) { availableLetters(list, nameOf) },
                                onLetterSelected = { letter ->
                                    val index = letterIndex(list, letter, nameOf)
                                    scope.launch { if (grid) gridState.scrollToItem(index) else listState.scrollToItem(index) }
                                },
                            )
                        }
                    } else null,
                ) {
                    if (grid) AlbumGrid(list, columns, onAlbum = { navigator.openAlbum(it.id) }, onAlbumLong = { albumForOptions = it }, state = gridState)
                    else AlbumList(list, onAlbum = { navigator.openAlbum(it.id) }, onAlbumLong = { albumForOptions = it }, state = listState)
                }
            }
        }
    }
    albumForOptions?.let { AlbumOptionsSheet(album = it, onDismiss = { albumForOptions = null }) }
}

// ---------------------------------------------------------------- Songs

@Composable
internal fun SongsTab(vm: LibraryViewModel) {
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val sort by vm.trackSort.collectAsStateWithLifecycle()
    val ascending by vm.trackAscending.collectAsStateWithLifecycle()
    val playback by vm.playbackState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var trackForOptions by remember { mutableStateOf<Track?>(null) }
    val sortOptions = remember {
        persistentListOf(
            SortOption(TrackSort.TITLE, "Title"),
            SortOption(TrackSort.ARTIST, "Artist"),
            SortOption(TrackSort.ALBUM, "Album"),
            SortOption(TrackSort.DURATION, "Duration"),
            SortOption(TrackSort.RECENTLY_ADDED, "Recently added"),
            SortOption(TrackSort.MOST_PLAYED, "Most played"),
            SortOption(TrackSort.RATING, "Rating"),
        )
    }
    val currentId = playback.currentTrack?.id
    val downloadedIds by rememberDownloadedIds()
    Column(Modifier.fillMaxSize()) {
        ListControlsRow(
            countLabel = tracks?.let { pluralStringResource(R.plurals.browse_count_songs, it.size, it.size) },
            start = { SortMenu(sortOptions, sort, ascending, onChange = vm::setTrackSort) },
            end = {
                IconButton(onClick = { vm.playAllSongs(shuffle = true) }) {
                    Icon(Icons.Rounded.Shuffle, contentDescription = stringResource(R.string.browse_action_shuffle_all))
                }
            },
        )
        val list = tracks
        when {
            list == null -> LoadingPlaceholder(PlaceholderStyle.LIST)
            list.isEmpty() -> EmptyState(title = stringResource(R.string.browse_empty_songs), body = stringResource(R.string.browse_empty_library_hint))
            else -> {
                val nameOf: (Track) -> String? = when (sort) {
                    TrackSort.ARTIST -> { t -> t.artistName }
                    TrackSort.ALBUM -> { t -> t.albumName }
                    else -> { t -> t.title }
                }
                ScrollerHost(
                    scroller = if ((sort == TrackSort.TITLE || sort == TrackSort.ARTIST || sort == TrackSort.ALBUM) && ascending) {
                        {
                            FastScroller(
                                availableLetters = remember(list, sort) { availableLetters(list, nameOf) },
                                onLetterSelected = { letter ->
                                    val index = letterIndex(list, letter, nameOf) + 1 // +1 for the header item
                                    scope.launch { listState.scrollToItem(index) }
                                },
                            )
                        }
                    } else null,
                ) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = listContentPadding(top = 4.dp)) {
                        item(key = "header", contentType = "header") {
                            PlayShuffleButtons(
                                onPlay = { vm.playAllSongs(shuffle = false) },
                                onShuffle = { vm.playAllSongs(shuffle = true) },
                                playLabel = stringResource(R.string.browse_action_play_all),
                                shuffleLabel = stringResource(R.string.browse_action_shuffle_all),
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                        items(list, key = { it.id }, contentType = { "track" }) { track ->
                            val index = list.indexOf(track)
                            TrackRow(
                                track = track,
                                onClick = { vm.playTrack(index) },
                                isCurrent = track.id == currentId,
                                isPlaying = playback.isPlaying,
                                isDownloaded = track.id in downloadedIds,
                                onLongClick = { trackForOptions = track },
                                onMoreClick = { trackForOptions = track },
                            )
                        }
                    }
                }
            }
        }
    }
    trackForOptions?.let { TrackOptionsSheet(track = it, onDismiss = { trackForOptions = null }) }
}

// ---------------------------------------------------------------- Playlists

@Composable
internal fun PlaylistsTab(vm: LibraryViewModel) {
    val navigator = LocalNavigator.current
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val grid by vm.playlistsGrid.collectAsStateWithLifecycle()
    val columns by vm.gridColumns.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ListControlsRow(
                countLabel = playlists?.let { pluralStringResource(R.plurals.browse_count_playlists, it.size, it.size) },
                end = { GridListToggle(isGrid = grid, onToggle = vm::setPlaylistsGrid) },
            )
            val list = playlists
            when {
                list == null -> LoadingPlaceholder(PlaceholderStyle.LIST)
                list.isEmpty() -> EmptyState(
                    title = stringResource(R.string.browse_empty_playlists),
                    body = stringResource(R.string.browse_empty_playlists_hint),
                    actionLabel = stringResource(R.string.browse_action_new_playlist),
                    onAction = { showCreate = true },
                )
                grid -> LazyVerticalGrid(
                    columns = gridCells(columns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = listContentPadding(top = 4.dp, horizontal = 12.dp, extraBottom = 96.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    gridItems(list, key = { it.id }, contentType = { "playlist" }) { playlist ->
                        PlaylistCard(playlist = playlist, onClick = { navigator.openPlaylist(playlist.id) })
                    }
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = listContentPadding(top = 4.dp, extraBottom = 96.dp)) {
                    items(list, key = { it.id }, contentType = { "playlist" }) { playlist ->
                        PlaylistRow(playlist = playlist, onClick = { navigator.openPlaylist(playlist.id) })
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { showCreate = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .padding(bottom = LocalContentBottomPadding.current),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.browse_action_new_playlist))
        }
    }
    if (showCreate) {
        RenameDialog(
            title = stringResource(R.string.browse_title_new_playlist),
            label = stringResource(R.string.browse_label_playlist_name),
            confirmLabel = stringResource(R.string.browse_action_create),
            onConfirm = vm::createPlaylist,
            onDismiss = { showCreate = false },
        )
    }
}

// ---------------------------------------------------------------- Genres

@Composable
internal fun GenresTab(vm: LibraryViewModel) {
    val navigator = LocalNavigator.current
    val genres by vm.genres.collectAsStateWithLifecycle()
    val list = genres
    when {
        list == null -> LoadingPlaceholder(PlaceholderStyle.LIST)
        list.isEmpty() -> EmptyState(title = stringResource(R.string.browse_empty_genres), icon = Icons.Rounded.Category, body = stringResource(R.string.browse_empty_library_hint))
        else -> LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = listContentPadding(top = 4.dp)) {
            item(key = "spacer") { Spacer(Modifier.height(4.dp)) }
            items(list, key = { it.name }, contentType = { "genre" }) { genre ->
                GenreRow(genre = genre, onClick = { navigator.openGenre(genre.name) })
            }
        }
    }
}
