package app.polyphony.ui.browse.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polyphony.R
import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.CatalogTrack
import app.polyphony.core.ui.components.AlbumCard
import app.polyphony.core.ui.components.LoadingPlaceholder
import app.polyphony.core.ui.components.PlaceholderStyle
import app.polyphony.core.ui.components.SectionHeader
import app.polyphony.core.ui.components.listContentPadding
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.browse.catalog.CatalogRow
import app.polyphony.ui.browse.catalog.requestTrackLabel
import app.polyphony.ui.common.AlbumOptionsSheet
import app.polyphony.ui.common.CatalogSongSheet
import app.polyphony.ui.common.CatalogSwipeRow
import app.polyphony.ui.common.PlayNowViewModel
import app.polyphony.ui.shell.LocalSnackbarHostState

/** For you, then Random picks (ExploreViewModel). */
@Composable
internal fun ExploreTab(viewModel: ExploreViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val playNow = hiltViewModel<PlayNowViewModel>().playNow
    val canPlayNow by playNow.available.collectAsStateWithLifecycle()
    var songForOptions by remember { mutableStateOf<CatalogTrack?>(null) }
    var albumForOptions by remember { mutableStateOf<app.polyphony.core.domain.model.Album?>(null) }
    var showAll by remember { mutableStateOf(false) }
    val snackbar = LocalSnackbarHostState.current
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { m ->
            snackbar.showSnackbar(if (m.arg == null) context.getString(m.text) else context.getString(m.text, m.arg))
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = listContentPadding(top = 4.dp, extraBottom = 96.dp)) {
        val songs = state.forYou
        if (songs == null) {
            item(key = "foryou-loading") { LoadingPlaceholder(PlaceholderStyle.LIST, count = 4) }
        } else if (songs.isNotEmpty()) {
            val visible = if (showAll) songs else songs.take(FOR_YOU_SHOWN)
            item(key = "foryou-header") { SectionHeader(title = stringResource(R.string.browse_home_for_you)) }
            items(visible, key = { "fy-" + it.key }, contentType = { "catalog-track" }) { track ->
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
                            canPlayNow && track.recordingId != null -> ({ playNow.play(track) })
                            else -> null
                        },
                        onLongClick = if (inLibrary == null) ({ songForOptions = track }) else null,
                    )
                }
            }
            if (songs.size > FOR_YOU_SHOWN) {
                item(key = "foryou-more") {
                    TextButton(onClick = { showAll = !showAll }, modifier = Modifier.padding(start = 8.dp)) {
                        Text(stringResource(if (showAll) R.string.browse_action_show_less else R.string.browse_action_show_more))
                    }
                }
            }
        }
        if (state.randomPicks.isNotEmpty()) {
            item(key = "random-header") {
                SectionHeader(
                    title = stringResource(R.string.browse_home_random_picks),
                    action = {
                        IconButton(onClick = viewModel::reshuffle) {
                            Icon(Icons.Rounded.Shuffle, contentDescription = stringResource(R.string.browse_action_reshuffle))
                        }
                    },
                )
            }
            items(state.randomPicks.chunked(2), key = { row -> "r-" + row.joinToString(",") { it.id } }, contentType = { "album-row" }) { row ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { album ->
                        AlbumCard(album, onClick = { navigator.openAlbum(album.id) }, onLongClick = { albumForOptions = album },
                                  modifier = Modifier.weight(1f))
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
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
    albumForOptions?.let { AlbumOptionsSheet(album = it, onDismiss = { albumForOptions = null }) }
}

/** For you's songs shown before Show more. */
private const val FOR_YOU_SHOWN = 8
