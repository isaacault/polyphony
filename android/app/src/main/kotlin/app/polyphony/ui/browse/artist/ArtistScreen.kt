package app.polyphony.ui.browse.artist

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polyphony.ui.common.FilledStar
import app.polyphony.ui.common.RatingMenu
import app.polyphony.R
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Track
import app.polyphony.core.ui.components.AlbumCard
import app.polyphony.core.ui.components.ArtistCard
import app.polyphony.core.ui.components.ArtworkImage
import app.polyphony.core.ui.components.ErrorState
import app.polyphony.core.ui.components.HeroHeader
import app.polyphony.core.ui.components.HeroScaffold
import app.polyphony.core.ui.components.HorizontalCarousel
import app.polyphony.core.ui.components.LoadingPlaceholder
import app.polyphony.core.ui.components.OverlayIconButton
import app.polyphony.core.ui.components.PlaceholderStyle
import app.polyphony.core.ui.components.PlayShuffleButtons
import app.polyphony.core.ui.components.SectionHeader
import app.polyphony.core.ui.components.TrackRow
import app.polyphony.core.ui.theme.ArtworkShapes
import app.polyphony.core.ui.theme.Dimens
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.browse.album.AlbumTrackDetails
import app.polyphony.ui.browse.catalog.CatalogAlbumCard
import app.polyphony.ui.browse.catalog.CatalogNote
import app.polyphony.ui.browse.catalog.Discography
import app.polyphony.ui.browse.catalog.DiscographyEntry
import app.polyphony.ui.browse.catalog.releaseTypeLabel
import app.polyphony.ui.common.TrackSwipeRow
import app.polyphony.ui.shell.LocalSnackbarHostState
import app.polyphony.ui.common.AlbumOptionsSheet
import app.polyphony.ui.common.CollectionDownloadButton
import app.polyphony.ui.common.rememberDownloadedIds
import app.polyphony.ui.common.TrackOptionsSheet

@Composable
fun ArtistScreen(viewModel: ArtistViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    var trackForOptions by remember { mutableStateOf<Track?>(null) }
    var albumForOptions by remember { mutableStateOf<Album?>(null) }
    var bioExpanded by rememberSaveable { mutableStateOf(false) }
    var showAllTop by rememberSaveable { mutableStateOf(false) }
    val artist = state.artist
    val downloadedIds by rememberDownloadedIds()
    val trackIds by viewModel.trackIds.collectAsStateWithLifecycle()

    val feedback by viewModel.feedback.collectAsStateWithLifecycle()
    val originalName by viewModel.originalName.collectAsStateWithLifecycle()
    var rateOpen by remember { mutableStateOf(false) }
    val snackbar = LocalSnackbarHostState.current
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { m ->
            snackbar.showSnackbar(if (m.arg == null) context.getString(m.text) else context.getString(m.text, m.arg))
        }
    }
    HeroScaffold(
        title = artist?.name ?: "",
        listState = listState,
        onBack = { navigator.back() },
        actions = {
            if (artist != null) {
                CollectionDownloadButton(trackIds = trackIds, tracks = { viewModel.allTracks() })
                if (feedback.usesRatings) {
                    Box {
                        OverlayIconButton(onClick = { rateOpen = true }) {
                            FilledStar(
                                rating = artist.rating,
                                idleTint = MaterialTheme.colorScheme.onSurface,
                                contentDescription = stringResource(R.string.feedback_rate),
                            )
                        }
                        RatingMenu(expanded = rateOpen, rating = artist.rating, onRate = viewModel::rate, onDismiss = { rateOpen = false })
                    }
                } else {
                    OverlayIconButton(onClick = {
                        haptics.performHapticFeedback(if (artist.starred) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                        viewModel.toggleFavorite()
                    }) {
                        Icon(
                            if (artist.starred) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = stringResource(R.string.browse_action_add_favorite),
                            tint = if (artist.starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
    ) { padding ->
        when {
            artist == null && state.refreshFailed -> Column(Modifier.fillMaxSize().padding(top = 96.dp)) {
                ErrorState(message = stringResource(R.string.browse_error_artist_unavailable), onRetry = viewModel::refresh)
            }
            artist == null -> Column(Modifier.fillMaxSize().padding(top = 96.dp)) { LoadingPlaceholder(PlaceholderStyle.GRID) }
            else -> {
                val heroUrl = artist.artworkUrl ?: state.albums.firstOrNull { it.artworkUrl != null }?.artworkUrl
                val visibleTop = if (showAllTop) state.topTracks else state.topTracks.take(5)
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = padding) {
                    item(key = "hero", contentType = "hero") {
                        HeroHeader(
                            artworkUrl = heroUrl,
                            title = artist.name,
                            artworkShape = if (artist.artworkUrl != null) CircleShape else ArtworkShapes.hero,
                            artwork = { mod ->
                                ArtworkImage(
                                    url = heroUrl,
                                    contentDescription = artist.name,
                                    modifier = mod,
                                    shape = if (artist.artworkUrl != null) CircleShape else ArtworkShapes.hero,
                                    placeholder = Icons.Rounded.Person,
                                )
                            },
                            meta = buildList {
                                originalName?.let(::add)
                                val albumCount = state.albums.size.takeIf { it > 0 } ?: artist.albumCount
                                if (albumCount > 0) add(pluralStringResource(R.plurals.browse_count_albums, albumCount, albumCount))
                            }.joinToString(" · ").ifBlank { null },
                            belowTitle = {
                                PlayShuffleButtons(
                                    onPlay = { viewModel.playAll(shuffle = false) },
                                    onShuffle = { viewModel.playAll(shuffle = true) },
                                    playLabel = stringResource(R.string.browse_action_play_all),
                                    shuffleLabel = stringResource(R.string.browse_action_shuffle),
                                )
                            },
                        )
                    }
                    if (!artist.biography.isNullOrBlank()) {
                        item(key = "bio", contentType = "bio") {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Dimens.screenPadding, vertical = 8.dp)
                                    .clickable { bioExpanded = !bioExpanded }
                                    .animateContentSize(),
                            ) {
                                Text(
                                    text = stripHtml(artist.biography.orEmpty()),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = if (bioExpanded) Int.MAX_VALUE else 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = stringResource(if (bioExpanded) R.string.browse_action_show_less else R.string.browse_action_read_more),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                    if (state.topTracksLoading || state.topTracks.isNotEmpty()) {
                        item(key = "top-header") { SectionHeader(title = stringResource(R.string.browse_artist_top_songs)) }
                        if (state.topTracksLoading) {
                            item(key = "top-loading") { LoadingPlaceholder(PlaceholderStyle.LIST, count = 3) }
                        }
                        items(visibleTop, key = { "top-" + it.id }, contentType = { "track" }) { track ->
                            TrackSwipeRow(track = track) {
                                TrackRow(
                                    track = track,
                                    onClick = { viewModel.playTopTrack(state.topTracks.indexOf(track)) },
                                    isCurrent = track.id == state.currentTrackId,
                                    isPlaying = state.isPlaying,
                                    // The page says whose songs these are and the artwork which album:
                                    // only who else is on it.
                                    subtitle = when (val credit = AlbumTrackDetails.credit(track, artist?.name)) {
                                        is AlbumTrackDetails.Credit.Featuring -> stringResource(R.string.browse_track_featuring, credit.names)
                                        is AlbumTrackDetails.Credit.Other -> credit.artist
                                        null -> null
                                    },
                                    fallbackSubtitle = false,
                                    isDownloaded = track.id in downloadedIds,
                                    onLongClick = { trackForOptions = track },
                                    onMoreClick = { trackForOptions = track },
                                )
                            }
                        }
                        if (state.topTracks.size > 5) {
                            item(key = "top-more") {
                                TextButton(onClick = { showAllTop = !showAllTop }, modifier = Modifier.padding(start = 8.dp)) {
                                    Text(stringResource(if (showAllTop) R.string.browse_action_show_less else R.string.browse_action_show_more))
                                }
                            }
                        }
                    }
                    // continuo: your albums and the ones you don't have, in one grid per release type.
                    state.discography.forEach { group ->
                        item(key = "dh-" + group.type) {
                            SectionHeader(
                                title = stringResource(releaseTypeLabel(group.type)),
                                subtitle = pluralStringResource(R.plurals.browse_count_albums, group.entries.size, group.entries.size),
                            )
                        }
                        items(group.entries.chunked(2), key = { row -> "d-" + row.joinToString(",") { it.key } }, contentType = { "album-row" }) { row ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                row.forEach { entry ->
                                    when (entry) {
                                        is DiscographyEntry.Owned -> Box(Modifier.weight(1f)) {
                                            val album = entry.album
                                            val partial = state.partials[album.id]
                                            AlbumCard(
                                                album = album,
                                                onClick = { navigator.openAlbum(album.id) },
                                                onLongClick = { albumForOptions = album },
                                                subtitle = listOfNotNull(
                                                    album.year?.toString(),
                                                    partial?.let { stringResource(R.string.browse_availability_partial, it.have, it.expected) },
                                                ).joinToString(" · "),
                                            )
                                        }
                                        is DiscographyEntry.Missing -> {
                                            val album = entry.album
                                            CatalogAlbumCard(
                                                album = album,
                                                busy = album.releaseGroupId in state.requesting,
                                                onRequest = viewModel::request,
                                                onClick = {
                                                    val libraryId = Discography.libraryId(album.availability)
                                                    when {
                                                        libraryId != null -> navigator.openAlbum(libraryId)
                                                        album.releaseGroupId != null -> navigator.openCatalogAlbum(album.releaseGroupId!!, album.title)
                                                    }
                                                },
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    if (state.discography.isEmpty() && state.albums.isNotEmpty()) {
                        item(key = "albums-header") {
                            SectionHeader(title = stringResource(R.string.browse_tab_albums), subtitle = pluralStringResource(R.plurals.browse_count_albums, state.albums.size, state.albums.size))
                        }
                        val rows = state.albums.chunked(2)
                        items(rows, key = { row -> "row-" + row.joinToString(",") { it.id } }, contentType = { "album-row" }) { row ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                row.forEach { album ->
                                    Box(Modifier.weight(1f)) {
                                        val partial = state.partials[album.id]
                                        AlbumCard(
                                            album = album,
                                            onClick = { navigator.openAlbum(album.id) },
                                            onLongClick = { albumForOptions = album },
                                            subtitle = listOfNotNull(
                                                album.year?.toString(),
                                                partial?.let { stringResource(R.string.browse_availability_partial, it.have, it.expected) },
                                            ).joinToString(" · "),
                                        )
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    if (state.catalogLoading) item(key = "catalog-loading") { CatalogNote(R.string.browse_catalog_loading) }
                    if (state.catalogFailed) item(key = "catalog-failed") { CatalogNote(R.string.browse_catalog_failed, isError = true) }
                    if (state.appearsOn.isNotEmpty()) {
                        item(key = "appears-header") { SectionHeader(title = stringResource(R.string.browse_artist_appears_on)) }
                        item(key = "appears") {
                            HorizontalCarousel(items = state.appearsOn, key = { "a-" + it.id }) { album ->
                                AlbumCard(
                                    album = album,
                                    onClick = { navigator.openAlbum(album.id) },
                                    onLongClick = { albumForOptions = album },
                                    width = Dimens.carouselArtwork,
                                    subtitle = album.artistName,
                                )
                            }
                        }
                    }
                    if (state.similarArtists.isNotEmpty()) {
                        item(key = "similar-header") { SectionHeader(title = stringResource(R.string.browse_artist_similar)) }
                        item(key = "similar") {
                            HorizontalCarousel(items = state.similarArtists, key = { "s-" + it.id }) { similar ->
                                ArtistCard(artist = similar, onClick = { navigator.openArtist(similar.id) }, width = 120.dp, subtitle = "")
                            }
                        }
                    }
                    item(key = "footer") { Spacer(Modifier.height(Dimens.sectionSpacing)) }
                }
            }
        }
    }
    trackForOptions?.let { TrackOptionsSheet(track = it, onDismiss = { trackForOptions = null }) }
    albumForOptions?.let { AlbumOptionsSheet(album = it, onDismiss = { albumForOptions = null }) }
}

private fun stripHtml(text: String): String =
    text.replace(Regex("<a [^>]*>(.*?)</a>"), "$1")
        .replace(Regex("<[^>]+>"), "")
        .replace("&quot;", "\"").replace("&amp;", "&").replace("&#39;", "'")
        .trim()
