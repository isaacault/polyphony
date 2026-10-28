package app.polyphony.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Artist
import app.polyphony.core.ui.components.AlbumCard
import app.polyphony.core.ui.components.AlbumRow
import app.polyphony.core.ui.components.ArtistCard
import app.polyphony.core.ui.components.ArtistRow
import app.polyphony.core.ui.components.listContentPadding
import app.polyphony.core.ui.theme.Dimens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

fun gridCells(columns: Int): GridCells =
    if (columns > 0) GridCells.Fixed(columns) else GridCells.Adaptive(Dimens.minGridTile)

/** Album grid used by Library, Favourites, Genre and "See all" screens. */
@Composable
fun AlbumGrid(
    albums: ImmutableList<Album>,
    columns: Int,
    onAlbum: (Album) -> Unit,
    onAlbumLong: (Album) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = listContentPadding(top = 4.dp, horizontal = 12.dp),
    header: (@Composable () -> Unit)? = null,
    /** Under the last item: how many there are ("214 albums"). */
    footer: String? = null,
) {
    LazyVerticalGrid(
        columns = gridCells(columns),
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (header != null) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") { header() }
        }
        items(albums, key = { it.id }, contentType = { "album" }) { album ->
            AlbumCard(album = album, onClick = { onAlbum(album) }, onLongClick = { onAlbumLong(album) })
        }
        if (footer != null) item(key = "footer", span = { GridItemSpan(maxLineSpan) }, contentType = "footer") { CountFooter(footer) }
    }
}

@Composable
fun AlbumList(
    albums: ImmutableList<Album>,
    onAlbum: (Album) -> Unit,
    onAlbumLong: (Album) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = listContentPadding(top = 4.dp),
    header: (@Composable () -> Unit)? = null,
    /** Under the last item: how many there are ("214 albums"). */
    footer: String? = null,
) {
    LazyColumn(state = state, modifier = modifier.fillMaxSize(), contentPadding = contentPadding) {
        if (header != null) item(key = "header", contentType = "header") { header() }
        items(albums, key = { it.id }, contentType = { "album" }) { album ->
            AlbumRow(album = album, onClick = { onAlbum(album) }, onLongClick = { onAlbumLong(album) }, onMoreClick = { onAlbumLong(album) })
        }
        if (footer != null) item(key = "footer", contentType = "footer") { CountFooter(footer) }
    }
}

@Composable
fun ArtistGrid(
    artists: ImmutableList<Artist>,
    columns: Int,
    onArtist: (Artist) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = listContentPadding(top = 4.dp, horizontal = 12.dp),
    header: (@Composable () -> Unit)? = null,
    /** Under the last item: how many there are ("214 albums"). */
    footer: String? = null,
) {
    LazyVerticalGrid(
        columns = gridCells(columns),
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (header != null) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") { header() }
        }
        items(artists, key = { it.id }, contentType = { "artist" }) { artist ->
            ArtistCard(artist = artist, onClick = { onArtist(artist) })
        }
        if (footer != null) item(key = "footer", span = { GridItemSpan(maxLineSpan) }, contentType = "footer") { CountFooter(footer) }
    }
}

@Composable
fun ArtistList(
    artists: ImmutableList<Artist>,
    onArtist: (Artist) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = listContentPadding(top = 4.dp),
    header: (@Composable () -> Unit)? = null,
    /** Under the last item: how many there are ("214 albums"). */
    footer: String? = null,
) {
    LazyColumn(state = state, modifier = modifier.fillMaxSize(), contentPadding = contentPadding) {
        if (header != null) item(key = "header", contentType = "header") { header() }
        items(artists, key = { it.id }, contentType = { "artist" }) { artist ->
            ArtistRow(artist = artist, onClick = { onArtist(artist) })
        }
        if (footer != null) item(key = "footer", contentType = "footer") { CountFooter(footer) }
    }
}

/** A list's count, under its last item. */
@Composable
fun CountFooter(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
    )
}

/** The sticky row above a library list: sort control on the left, view toggles on the right. */
@Composable
fun ListControlsRow(
    modifier: Modifier = Modifier,
    countLabel: String? = null,
    start: @Composable () -> Unit = {},
    end: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        start()
        Spacer(Modifier.weight(1f))
        if (countLabel != null) {
            Text(
                countLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        end()
    }
}

/**
 * Sends a list back to the top when [order] (its sort, direction and filters) changes. Keyed lazy
 * lists otherwise hold on to the first visible item, so a re-sort leaves you looking at the same
 * rows and seems to do nothing.
 *
 * Pass the list being shown as [content]: the jump waits until it is replaced, because jumping
 * while the old rows are still there would let the key tracking follow them to their new place.
 * Pass `null` when the list starts with an item whose key never moves (a header), which keeps
 * the top pinned on its own. The first composition never scrolls, so a restored position stays.
 */
@Composable
fun ScrollToTopOnReorder(order: Any?, content: Any?, scrollToTop: suspend () -> Unit) {
    val applied = remember { arrayOf(order) }
    val latestContent by rememberUpdatedState(content)
    val latestScroll by rememberUpdatedState(scrollToTop)
    LaunchedEffect(order) {
        if (order == applied[0]) return@LaunchedEffect
        applied[0] = order
        val stale = latestContent
        // An order that happens to produce an equal list never emits a new one: don't wait forever.
        if (stale != null) withTimeoutOrNull(REORDER_WAIT_MS) { snapshotFlow { latestContent }.first { it !== stale } }
        latestScroll()
    }
}

private const val REORDER_WAIT_MS = 2_000L

/** Box helper so a FastScroller can overlay a list that fills the tab. */
@Composable
fun ScrollerHost(
    modifier: Modifier = Modifier,
    scroller: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        // Keep row content clear of the alphabet rail, which overlays the end edge.
        Box(Modifier.fillMaxSize().padding(end = if (scroller != null) 32.dp else 0.dp)) { content() }
        if (scroller != null) {
            Box(Modifier.align(Alignment.CenterEnd).padding(bottom = 8.dp)) { scroller() }
        }
    }
}
