package app.polyphony.ui.browse.catalog

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polyphony.R
import app.polyphony.core.domain.model.Artist
import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.CatalogAlbum
import app.polyphony.core.domain.model.CatalogArtist
import app.polyphony.core.ui.components.ArtistCard
import app.polyphony.core.ui.components.ArtworkImage
import app.polyphony.core.ui.components.ErrorState
import app.polyphony.core.ui.components.HeroHeader
import app.polyphony.core.ui.components.HeroScaffold
import app.polyphony.core.ui.components.HorizontalCarousel
import app.polyphony.core.ui.components.SectionHeader
import app.polyphony.core.ui.theme.ArtworkShapes
import app.polyphony.core.ui.theme.Dimens
import app.polyphony.navigation.LocalNavigator
import app.polyphony.navigation.PolyphonyNavigator
import app.polyphony.ui.shell.LocalSnackbarHostState

/**
 * Any artist's page from the catalogue (#144), laid out like a library artist's: picture,
 * biography, top songs, the discography and similar artists. What isn't in the library carries
 * the request mark; songs and albums open their album page.
 */
@Composable
fun CatalogArtistScreen(viewModel: CatalogArtistViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val snackbar = LocalSnackbarHostState.current
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var bioExpanded by rememberSaveable { mutableStateOf(false) }
    var showAllTop by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { m ->
            snackbar.showSnackbar(if (m.arg == null) context.getString(m.text) else context.getString(m.text, m.arg))
        }
    }

    HeroScaffold(title = state.name, listState = listState, onBack = { navigator.back() }) { padding ->
        if (state.failed) {
            Column(Modifier.fillMaxSize().padding(padding).padding(top = 96.dp)) {
                ErrorState(message = stringResource(R.string.browse_catalog_failed), onRetry = viewModel::load)
            }
            return@HeroScaffold
        }
        val shape = if (state.hasArtistPicture) CircleShape else ArtworkShapes.hero
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = padding) {
            item(key = "hero", contentType = "hero") {
                HeroHeader(
                    artworkUrl = state.artworkUrl,
                    title = state.name,
                    artworkShape = shape,
                    artwork = { mod ->
                        ArtworkImage(url = state.artworkUrl, contentDescription = state.name, modifier = mod, shape = shape, placeholder = Icons.Rounded.Person)
                    },
                    meta = listOfNotNull(
                        state.disambiguation,
                        state.albumCount.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.browse_count_albums, it, it) },
                        stringResource(R.string.browse_catalog_artist_subtitle),
                    ).joinToString(" · "),
                )
            }
            state.biography?.let { bio ->
                item(key = "bio", contentType = "bio") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Dimens.screenPadding, vertical = 8.dp)
                            .clickable { bioExpanded = !bioExpanded }
                            .animateContentSize(),
                    ) {
                        Text(
                            text = bio,
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
            if (state.topTracks.isNotEmpty()) {
                val visible = if (showAllTop) state.topTracks else state.topTracks.take(5)
                item(key = "top-header") { SectionHeader(title = stringResource(R.string.browse_artist_top_songs)) }
                items(visible, key = { "top-" + it.key }, contentType = { "catalog-track" }) { track ->
                    val inLibrary = (track.availability as? Availability.InLibrary)?.libraryAlbumId
                    CatalogRow(
                        title = track.title,
                        subtitle = track.albumTitle,
                        artworkUrl = null,
                        availability = track.availability,
                        releaseGroupId = track.albumReleaseGroupId,
                        busy = track.requestKeys.any { it in state.requesting },
                        requestLabel = stringResource(requestTrackLabel(track)),
                        onRequest = { viewModel.requestTrack(track) },
                        onClick = when {
                            inLibrary != null -> ({ navigator.openAlbum(inLibrary) })
                            track.albumReleaseGroupId != null -> ({ navigator.openCatalogAlbum(track.albumReleaseGroupId!!, track.albumTitle ?: track.title) })
                            else -> null
                        },
                    )
                }
                if (state.topTracks.size > 5) {
                    item(key = "top-more") {
                        TextButton(onClick = { showAllTop = !showAllTop }, modifier = Modifier.padding(start = 8.dp)) {
                            Text(stringResource(if (showAllTop) R.string.browse_action_show_less else R.string.browse_action_show_more))
                        }
                    }
                }
            }
            if (state.loading) item(key = "loading") { CatalogNote(R.string.browse_catalog_loading) }
            if (state.isEmpty) item(key = "empty") { CatalogNote(R.string.browse_catalog_empty) }
            // Releases continuo matched to the library under another artist name: yours, so first and not dimmed.
            if (state.inLibrary.isNotEmpty()) {
                item(key = "h-library") { SectionHeader(title = stringResource(R.string.browse_catalog_in_library)) }
                albumGrid("lib", state.inLibrary, state, viewModel::request, navigator)
            }
            state.groups.forEach { group ->
                item(key = "h-" + group.type) { SectionHeader(title = stringResource(releaseTypeLabel(group.type))) }
                albumGrid(group.type.name, group.albums, state, viewModel::request, navigator)
            }
            if (state.similarArtists.isNotEmpty()) {
                item(key = "similar-header") { SectionHeader(title = stringResource(R.string.browse_artist_similar)) }
                item(key = "similar") {
                    HorizontalCarousel(items = state.similarArtists, key = { "s-" + it.key }) { similar ->
                        val libraryId = state.libraryArtistIds[similar.key]
                        ArtistCard(
                            artist = similar.asArtist(),
                            onClick = {
                                when {
                                    libraryId != null -> navigator.openArtist(libraryId)
                                    similar.musicBrainzId != null -> navigator.openCatalogArtist(similar.musicBrainzId!!, similar.name)
                                }
                            },
                            width = 120.dp,
                            subtitle = "",
                        )
                    }
                }
            }
            item(key = "footer") { Spacer(Modifier.height(Dimens.sectionSpacing)) }
        }
    }
}

/** Two-up album cards like a library artist's, opening the library album or the catalogue album page. */
private fun LazyListScope.albumGrid(
    prefix: String,
    albums: List<CatalogAlbum>,
    state: CatalogArtistUiState,
    onRequest: (String) -> Unit,
    navigator: PolyphonyNavigator,
) {
    items(albums.chunked(2), key = { row -> "$prefix-" + row.joinToString(",") { it.key } }, contentType = { "album-row" }) { row ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            row.forEach { album ->
                val libraryId = Discography.libraryId(album.availability)
                CatalogAlbumCard(
                    album = album,
                    busy = album.releaseGroupId in state.requesting,
                    onRequest = onRequest,
                    onClick = {
                        when {
                            libraryId != null -> navigator.openAlbum(libraryId)
                            album.releaseGroupId != null -> navigator.openCatalogAlbum(album.releaseGroupId!!, album.title)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

private fun CatalogArtist.asArtist() = Artist(id = key, serverId = "", name = name, artworkUrl = artworkUrl)
