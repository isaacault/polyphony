package app.polyphony.ui.browse.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polyphony.R
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.CatalogArtist
import app.polyphony.core.ui.components.AlbumRow
import app.polyphony.core.ui.components.ArtworkImage
import app.polyphony.core.ui.components.HorizontalCarousel
import app.polyphony.core.ui.components.LoadingPlaceholder
import app.polyphony.core.ui.components.MediaRow
import app.polyphony.core.ui.components.PlaceholderStyle
import app.polyphony.core.ui.components.SectionHeader
import app.polyphony.core.ui.components.listContentPadding
import app.polyphony.core.ui.theme.Dimens
import app.polyphony.navigation.LocalNavigator
import app.polyphony.navigation.PolyphonyNavigator
import app.polyphony.ui.browse.catalog.CatalogAlbumCard
import app.polyphony.ui.browse.catalog.CatalogRow
import app.polyphony.ui.browse.catalog.Discography
import app.polyphony.ui.browse.catalog.catalogAlbumSubtitle
import app.polyphony.ui.common.AlbumOptionsSheet
import app.polyphony.ui.shell.LocalSnackbarHostState

/** New releases (tiles), artists for you (a grid), albums for you, playlists for you (lists). */
@Composable
internal fun ExploreTab(viewModel: ExploreViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    var albumForOptions by remember { mutableStateOf<Album?>(null) }
    val snackbar = LocalSnackbarHostState.current
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { m ->
            snackbar.showSnackbar(if (m.arg == null) context.getString(m.text) else context.getString(m.text, m.arg))
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = listContentPadding(top = 4.dp, extraBottom = 96.dp)) {
        if (state.loading) {
            item(key = "loading") { LoadingPlaceholder(PlaceholderStyle.CAROUSEL) }
        }
        if (state.newReleases.isNotEmpty()) {
            item(key = "new-header") { SectionHeader(title = stringResource(R.string.explore_new_releases)) }
            item(key = "new") {
                HorizontalCarousel(items = state.newReleases, key = { "n-" + it.key }) { album ->
                    val libraryId = Discography.libraryId(album.availability)
                    CatalogAlbumCard(
                        album = album,
                        busy = album.releaseGroupId in state.requesting,
                        onRequest = viewModel::request,
                        onClick = { openCatalogAlbum(navigator, libraryId, album.releaseGroupId, album.title) },
                        modifier = Modifier.width(Dimens.carouselArtwork),
                        subtitle = catalogAlbumSubtitle(album, byArtist = true),
                    )
                }
            }
        }
        if (state.artists.isNotEmpty()) {
            item(key = "artists-header") { SectionHeader(title = stringResource(R.string.explore_artists)) }
            item(key = "artists") {
                ArtistGrid(state.artists) { artist ->
                    artist.musicBrainzId?.let { navigator.openCatalogArtist(it, artist.name) }
                }
            }
        }
        if (state.albums.isNotEmpty()) {
            item(key = "albums-header") {
                SectionHeader(
                    title = stringResource(R.string.explore_albums),
                    action = {
                        IconButton(onClick = viewModel::reshuffle) {
                            Icon(Icons.Rounded.Shuffle, contentDescription = stringResource(R.string.browse_action_reshuffle))
                        }
                    },
                )
            }
            items(state.albums, key = { a -> when (a) { is ExploreAlbum.New -> "an-" + a.album.key; is ExploreAlbum.Yours -> "ay-" + a.album.id } },
                  contentType = { "album" }) { entry ->
                when (entry) {
                    is ExploreAlbum.New -> {
                        val album = entry.album
                        val libraryId = Discography.libraryId(album.availability)
                        CatalogRow(
                            title = album.title,
                            subtitle = catalogAlbumSubtitle(album, byArtist = true),
                            artworkUrl = album.artworkUrl,
                            availability = album.availability,
                            releaseGroupId = album.releaseGroupId,
                            busy = album.releaseGroupId in state.requesting,
                            requestLabel = stringResource(R.string.browse_action_request_album),
                            onRequest = viewModel::request,
                            onClick = { openCatalogAlbum(navigator, libraryId, album.releaseGroupId, album.title) },
                        )
                    }
                    is ExploreAlbum.Yours -> AlbumRow(
                        album = entry.album,
                        onClick = { navigator.openAlbum(entry.album.id) },
                        onLongClick = { albumForOptions = entry.album },
                    )
                }
            }
        }
        if (state.playlists.isNotEmpty()) {
            item(key = "playlists-header") { SectionHeader(title = stringResource(R.string.explore_playlists)) }
            items(state.playlists, key = { "p-" + it.id }, contentType = { "playlist" }) { playlist ->
                MediaRow(
                    title = playlist.title,
                    subtitle = playlist.description ?: stringResource(R.string.explore_playlist_source),
                    artworkUrl = null,
                    onClick = { navigator.openDiscoveryPlaylist(playlist.id, playlist.title) },
                    placeholder = Icons.AutoMirrored.Rounded.QueueMusic,
                )
            }
        }
        item(key = "footer") { Spacer(Modifier.height(Dimens.sectionSpacing)) }
    }
    albumForOptions?.let { AlbumOptionsSheet(album = it, onDismiss = { albumForOptions = null }) }
}

private fun openCatalogAlbum(navigator: PolyphonyNavigator, libraryId: String?, releaseGroupId: String?, title: String) {
    when {
        libraryId != null -> navigator.openAlbum(libraryId)
        releaseGroupId != null -> navigator.openCatalogAlbum(releaseGroupId, title)
    }
}

/** Two columns of wide tiles, like Home's quick picks: a round picture and the artist's name. */
@Composable
private fun ArtistGrid(artists: List<CatalogArtist>, onClick: (CatalogArtist) -> Unit) {
    Column(Modifier.padding(horizontal = Dimens.screenPadding), verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
        artists.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                row.forEach { artist ->
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(TILE_HEIGHT)
                            .clip(RoundedCornerShape(TILE_RADIUS))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { onClick(artist) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ArtworkImage(
                            url = artist.artworkUrl,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp).size(TILE_HEIGHT - 8.dp),
                            shape = CircleShape,
                            placeholder = Icons.Rounded.Person,
                        )
                        Text(
                            text = artist.name,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private val TILE_HEIGHT = 56.dp
private val TILE_GAP = 8.dp
private val TILE_RADIUS = 6.dp
