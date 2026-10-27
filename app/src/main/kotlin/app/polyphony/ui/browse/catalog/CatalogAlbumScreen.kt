package app.polyphony.ui.browse.catalog

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polyphony.R
import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.CatalogAlbumTrack
import app.polyphony.core.domain.model.CatalogTrack
import app.polyphony.core.ui.components.ErrorState
import app.polyphony.core.ui.components.HeroHeader
import app.polyphony.core.ui.components.HeroScaffold
import app.polyphony.core.ui.components.QualityBadge
import app.polyphony.core.ui.theme.Dimens
import app.polyphony.core.ui.util.formatDuration
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.common.CatalogSongSheet
import app.polyphony.ui.shell.LocalSnackbarHostState

/** An album that isn't in the library (#145): artwork, tracklist and Request, from the catalogue. */
@Composable
fun CatalogAlbumScreen(viewModel: CatalogAlbumViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val canPlayNow by viewModel.canPlayNow.collectAsStateWithLifecycle()
    // Its songs play now (continuo#12) unless it's yours or on the server already: then the album
    // itself is a tap away (Open, Add).
    val playable = canPlayNow && state.availability !is Availability.InLibrary && state.availability !is Availability.OnServer
    val navigator = LocalNavigator.current
    var songForOptions by remember { mutableStateOf<CatalogTrack?>(null) }
    val snackbar = LocalSnackbarHostState.current
    val context = LocalContext.current
    val listState = rememberLazyListState()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { m ->
            snackbar.showSnackbar(if (m.arg == null) context.getString(m.text) else context.getString(m.text, m.arg))
        }
    }

    HeroScaffold(title = state.title, listState = listState, onBack = { navigator.back() }) { padding ->
        if (state.failed) {
            Column(Modifier.fillMaxSize().padding(padding).padding(top = 96.dp)) {
                ErrorState(message = stringResource(R.string.browse_catalog_album_failed), onRetry = viewModel::load)
            }
            return@HeroScaffold
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = padding) {
            item(key = "hero", contentType = "hero") {
                HeroHeader(
                    artworkUrl = state.artworkUrl,
                    title = state.title,
                    meta = listOfNotNull(
                        state.artist,
                        state.year?.toString(),
                        Discography.typeOf(state.albumType).takeIf { it != ReleaseType.ALBUM && state.albumType != null }
                            ?.let { stringResource(releaseTypeName(it)) },
                    ).joinToString(" · ").ifBlank { null },
                    belowTitle = {
                        AlbumAction(
                            availability = state.availability,
                            busy = state.busy,
                            onRequest = viewModel::request,
                            onOpenLibrary = navigator::openAlbum,
                            onOpenRequests = { navigator.openRequests(it) },
                        )
                    },
                )
            }
            if (state.loading) item(key = "loading") { CatalogNote(R.string.browse_catalog_album_loading) }
            if (!state.loading && state.discs.isEmpty()) item(key = "no-tracks") { CatalogNote(R.string.browse_catalog_album_no_tracks) }
            state.discs.forEach { disc ->
                if (state.showDiscLabels) {
                    item(key = "disc-${disc.number}") {
                        Text(
                            stringResource(R.string.browse_disc_header, disc.number),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = Dimens.screenPadding, end = Dimens.screenPadding, top = 12.dp, bottom = 4.dp),
                        )
                    }
                }
                items(disc.tracks, key = { "t-${disc.number}-${it.position}" }, contentType = { "catalog-track" }) { track ->
                    val canPlay = playable && track.recordingId != null
                    CatalogTrackLine(
                        track,
                        onPlay = if (canPlay) ({ viewModel.playTrack(track) }) else null,
                        onOptions = if (canPlay) ({ songForOptions = viewModel.song(track) }) else null,
                    )
                }
            }
        }
    }
    songForOptions?.let { song -> CatalogSongSheet(track = song, onOpenAlbum = null, onDismiss = { songForOptions = null }) }
}

/**
 * What can be done with the album: Request it, follow its request, or open it when it's (partly)
 * in the library already.
 */
@Composable
private fun AlbumAction(
    availability: Availability,
    busy: Boolean,
    onRequest: () -> Unit,
    onOpenLibrary: (String) -> Unit,
    onOpenRequests: (Long?) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (availability) {
            Availability.Requestable -> Button(onClick = onRequest, enabled = !busy) {
                Icon(Icons.Rounded.LibraryAdd, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.browse_action_request))
            }
            is Availability.Requested -> {
                availabilityLabel(availability)?.let { QualityBadge(label = stringResource(it)) }
                TextButton(onClick = { onOpenRequests(availability.requestId) }) { Text(stringResource(R.string.browse_catalog_see_request)) }
            }
            is Availability.InLibrary -> availability.libraryAlbumId?.let { id ->
                FilledTonalButton(onClick = { onOpenLibrary(id) }) { Text(stringResource(R.string.browse_catalog_open_library)) }
            }
            is Availability.Partial -> {
                if (availability.requestable) {
                    Button(onClick = onRequest, enabled = !busy) {
                        Icon(Icons.Rounded.LibraryAdd, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.browse_action_request_album))
                    }
                }
                availability.libraryAlbumId?.let { id ->
                    FilledTonalButton(onClick = { onOpenLibrary(id) }) {
                        Text(stringResource(R.string.browse_catalog_open_library_partial, availability.have, availability.expected))
                    }
                }
            }
            is Availability.OnServer -> Button(onClick = onRequest, enabled = !busy) {
                Icon(Icons.Rounded.LibraryAdd, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.browse_action_add))
            }
            Availability.CatalogOnly -> Unit
        }
    }
}

/** A tracklist line; with [onPlay], tapping it plays the song now (continuo#12). */
@Composable
private fun CatalogTrackLine(track: CatalogAlbumTrack, onPlay: (() -> Unit)? = null, onOptions: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onPlay != null) Modifier.combinedClickable(onClick = onPlay, onLongClick = onOptions) else Modifier)
            .heightIn(min = 48.dp)
            .padding(horizontal = Dimens.screenPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            track.position.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(28.dp),
        )
        Text(
            track.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = Dimens.itemSpacing),
        )
        track.durationMs?.let {
            Text(formatDuration(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A release type as a word in the header line ("EP", "Single"). */
private fun releaseTypeName(type: ReleaseType): Int = when (type) {
    ReleaseType.ALBUM -> R.string.browse_kind_album
    ReleaseType.EP -> R.string.browse_catalog_type_ep
    ReleaseType.SINGLE -> R.string.browse_catalog_type_single
    ReleaseType.OTHER -> R.string.browse_catalog_other
}
