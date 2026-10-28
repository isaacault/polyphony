package app.polyphony.ui.browse.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.polyphony.R
import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.CatalogTrack
import app.polyphony.core.domain.model.DiscoveryPlaylistPage
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.core.ui.components.ErrorState
import app.polyphony.core.ui.components.LoadingPlaceholder
import app.polyphony.core.ui.components.PlaceholderStyle
import app.polyphony.core.ui.components.listContentPadding
import app.polyphony.core.ui.theme.Dimens
import app.polyphony.navigation.DiscoveryPlaylistRoute
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.browse.catalog.CatalogArtistPageLogic
import app.polyphony.ui.browse.catalog.CatalogRequester
import app.polyphony.ui.browse.catalog.CatalogRow
import app.polyphony.ui.browse.catalog.requestTrackLabel
import app.polyphony.ui.common.CatalogSongSheet
import app.polyphony.ui.common.CatalogSwipeRow
import app.polyphony.ui.common.PlayNow
import app.polyphony.ui.common.PlayNowViewModel
import app.polyphony.ui.common.QueueSong
import app.polyphony.ui.common.UiMessage
import app.polyphony.ui.shell.LocalSnackbarHostState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiscoveryPlaylistUiState(
    val title: String = "",
    val description: String? = null,
    val tracks: ImmutableList<CatalogTrack> = persistentListOf(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    val requesting: Set<String> = emptySet(),
)

/** One of ListenBrainz's playlists for you (Explore): its songs, to play now or request. */
@HiltViewModel
class DiscoveryPlaylistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val servers: ServerRepository,
    private val requests: RequestRepository,
    private val playNow: PlayNow,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<DiscoveryPlaylistRoute>()
    private val page = MutableStateFlow<DiscoveryPlaylistPage?>(null)
    private val failed = MutableStateFlow(false)
    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    val events: Flow<UiMessage> = messages.receiveAsFlow()
    private val requester = CatalogRequester(viewModelScope, servers, requests, messages)

    val state: StateFlow<DiscoveryPlaylistUiState> = combine(page, failed, requester.requested, requester.requesting) { p, f, requested, busy ->
        DiscoveryPlaylistUiState(
            title = p?.title ?: route.title,
            description = p?.description,
            tracks = CatalogArtistPageLogic.topTracks(p?.tracks.orEmpty(), requested).toImmutableList(),
            loading = p == null && !f,
            failed = f,
            requesting = busy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiscoveryPlaylistUiState(title = route.title))

    init { load() }

    fun load() {
        viewModelScope.launch {
            failed.value = false
            val server = servers.getActive()?.takeIf { requests.supports(it) }
            page.value = try {
                server?.let { requests.discoveryPlaylist(it, route.id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (page.value == null) failed.value = true
        }
    }

    /** The playlist from [index] as the queue: each song fetched by Play now as it comes up. */
    fun play(index: Int) {
        val s = state.value
        playNow.playAll(s.tracks.map { QueueSong.Fetch(it) }, index, queueTitle = s.title)
    }

    fun requestTrack(track: CatalogTrack) = requester.requestTrack(track)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryPlaylistScreen(viewModel: DiscoveryPlaylistViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val canPlayNow by hiltViewModel<PlayNowViewModel>().playNow.available.collectAsStateWithLifecycle()
    var songForOptions by remember { mutableStateOf<CatalogTrack?>(null) }
    val snackbar = LocalSnackbarHostState.current
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { m ->
            snackbar.showSnackbar(if (m.arg == null) context.getString(m.text) else context.getString(m.text, m.arg))
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.browse_action_back))
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.failed -> Column(Modifier.fillMaxSize().padding(padding).padding(top = 96.dp)) {
                ErrorState(message = stringResource(R.string.explore_playlist_failed), onRetry = viewModel::load)
            }
            state.loading -> Column(Modifier.padding(padding)) { LoadingPlaceholder(PlaceholderStyle.LIST) }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = listContentPadding(extraBottom = 96.dp)) {
                item(key = "header") {
                    Column(Modifier.padding(horizontal = Dimens.screenPadding, vertical = 8.dp)) {
                        state.description?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4,
                                 overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(12.dp))
                        }
                        if (canPlayNow && state.tracks.isNotEmpty()) {
                            Button(onClick = { viewModel.play(0) }) {
                                Row {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.browse_action_play))
                                }
                            }
                        }
                    }
                }
                itemsIndexed(state.tracks, key = { _, t -> "t-" + t.key }, contentType = { _, _ -> "catalog-track" }) { index, track ->
                    val inLibrary = (track.availability as? Availability.InLibrary)?.libraryAlbumId
                    CatalogSwipeRow(track) {
                        CatalogRow(
                            title = track.title,
                            subtitle = track.artist,
                            artworkUrl = track.artworkUrl,
                            availability = track.availability,
                            releaseGroupId = track.albumReleaseGroupId,
                            busy = track.requestKeys.any { it in state.requesting },
                            requestLabel = stringResource(requestTrackLabel(track)),
                            onRequest = { viewModel.requestTrack(track) },
                            onClick = when {
                                inLibrary != null -> ({ navigator.openAlbum(inLibrary) })
                                canPlayNow && track.recordingId != null -> ({ viewModel.play(index) })
                                else -> null
                            },
                            onLongClick = if (inLibrary == null) ({ songForOptions = track }) else null,
                        )
                    }
                }
            }
        }
    }
    songForOptions?.let { song ->
        CatalogSongSheet(
            track = song,
            onOpenAlbum = song.albumReleaseGroupId?.let { rg -> { navigator.openCatalogAlbum(rg, song.albumTitle ?: song.title) } },
            onDismiss = { songForOptions = null },
        )
    }
}
