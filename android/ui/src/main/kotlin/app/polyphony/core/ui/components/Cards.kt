package app.polyphony.core.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Artist
import app.polyphony.core.domain.model.Playlist
import app.polyphony.core.ui.theme.ArtworkShapes

/** Square artwork tile with title and subtitle. Pass [width] inside carousels; grids fill. */
@Composable
fun AlbumCard(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    width: Dp? = null,
    subtitle: String? = null,
) {
    val base = if (width != null) modifier.width(width) else modifier.fillMaxWidth()
    Column(
        modifier = base
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ArtworkImage(
            url = album.artworkUrl,
            contentDescription = album.name,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            shape = ArtworkShapes.gridTile,
        )
        Text(
            text = album.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Text(
            text = subtitle ?: albumSubtitle(album),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** The artist alone; the year is intentionally omitted to keep album lists uncluttered. */
fun albumSubtitle(album: Album): String = album.artistName ?: "Unknown artist"

/** Circular artist artwork with the name underneath. */
@Composable
fun ArtistCard(
    artist: Artist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    width: Dp? = null,
    subtitle: String? = null,
) {
    val base = if (width != null) modifier.width(width) else modifier.fillMaxWidth()
    Column(
        modifier = base
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ArtworkImage(
            url = artist.artworkUrl,
            contentDescription = artist.name,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            shape = CircleShape,
            placeholder = Icons.Rounded.Person,
        )
        Text(
            text = artist.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        val sub = subtitle ?: artist.albumCount.takeIf { it > 0 }?.let { "$it ${if (it == 1) "album" else "albums"}" }
        if (sub != null) {
            Text(
                text = sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Playlist tile; uses a mosaic when several artwork URLs are supplied and no cover exists. */
@Composable
fun PlaylistCard(
    playlist: Playlist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    width: Dp? = null,
    mosaicUrls: List<String?> = emptyList(),
) {
    val base = if (width != null) modifier.width(width) else modifier.fillMaxWidth()
    Column(
        modifier = base
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (playlist.artworkUrl == null && mosaicUrls.count { it != null } >= 4) {
            ArtworkMosaic(
                urls = mosaicUrls,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                shape = ArtworkShapes.gridTile,
            )
        } else {
            ArtworkImage(
                url = playlist.artworkUrl,
                contentDescription = playlist.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                shape = ArtworkShapes.gridTile,
                placeholder = Icons.AutoMirrored.Rounded.QueueMusic,
            )
        }
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Text(
            text = "${playlist.trackCount} ${if (playlist.trackCount == 1) "song" else "songs"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
fun GenreChip(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AssistChip(
        onClick = onClick,
        label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
    )
}
