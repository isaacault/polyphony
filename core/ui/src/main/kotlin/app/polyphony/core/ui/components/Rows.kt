package app.polyphony.core.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Artist
import app.polyphony.core.domain.model.Genre
import app.polyphony.core.domain.model.Playlist
import app.polyphony.core.domain.model.Track
import app.polyphony.core.ui.theme.ArtworkShapes
import app.polyphony.core.ui.theme.Dimens
import app.polyphony.core.ui.util.formatDurationLong

/** Generic media row: artwork, two text lines, optional overflow. */
@Composable
fun MediaRow(
    title: String,
    subtitle: String?,
    artworkUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onMoreClick: (() -> Unit)? = null,
    shape: Shape = ArtworkShapes.listThumb,
    placeholder: ImageVector = Icons.Rounded.Person,
    showChevron: Boolean = false,
    artworkSize: Dp = Dimens.listArtwork,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick ?: onMoreClick)
            .padding(start = Dimens.screenPadding, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(url = artworkUrl, contentDescription = null, modifier = Modifier.size(artworkSize), shape = shape, placeholder = placeholder)
        Spacer(Modifier.width(Dimens.itemSpacing))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            showChevron -> Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
            else -> Spacer(Modifier.width(Dimens.itemSpacing))
        }
    }
}

@Composable
fun AlbumRow(album: Album, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onMoreClick: (() -> Unit)? = null) {
    MediaRow(
        title = album.name,
        subtitle = albumSubtitle(album),
        artworkUrl = album.artworkUrl,
        onClick = onClick,
        onLongClick = onLongClick,
        onMoreClick = onMoreClick,
        modifier = modifier,
    )
}

@Composable
fun ArtistRow(artist: Artist, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null) {
    MediaRow(
        title = artist.name,
        subtitle = artist.albumCount.takeIf { it > 0 }?.let { "$it ${if (it == 1) "album" else "albums"}" },
        artworkUrl = artist.artworkUrl,
        onClick = onClick,
        onLongClick = onLongClick,
        shape = CircleShape,
        placeholder = Icons.Rounded.Person,
        modifier = modifier,
    )
}

@Composable
fun PlaylistRow(playlist: Playlist, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onMoreClick: (() -> Unit)? = null) {
    val meta = buildList {
        add("${playlist.trackCount} ${if (playlist.trackCount == 1) "song" else "songs"}")
        if (playlist.durationMs > 0) add(formatDurationLong(playlist.durationMs))
        playlist.owner?.let { add(it) }
    }.joinToString(" · ")
    MediaRow(
        title = playlist.name,
        subtitle = meta,
        artworkUrl = playlist.artworkUrl,
        onClick = onClick,
        onLongClick = onLongClick,
        onMoreClick = onMoreClick,
        placeholder = Icons.AutoMirrored.Rounded.QueueMusic,
        modifier = modifier,
    )
}

@Composable
fun GenreRow(genre: Genre, onClick: () -> Unit, modifier: Modifier = Modifier) {
    MediaRow(
        title = genre.name,
        subtitle = buildList {
            if (genre.albumCount > 0) add("${genre.albumCount} ${if (genre.albumCount == 1) "album" else "albums"}")
            if (genre.trackCount > 0) add("${genre.trackCount} ${if (genre.trackCount == 1) "song" else "songs"}")
        }.joinToString(" · ").ifBlank { null },
        artworkUrl = null,
        onClick = onClick,
        placeholder = Icons.Rounded.Category,
        showChevron = true,
        artworkSize = 44.dp,
        modifier = modifier,
    )
}

/** Compact track tile for carousels ("Continue listening"). */
@Composable
fun TrackCard(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    width: Dp = 140.dp,
) {
    Column(
        modifier = modifier
            .width(width)
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp),
    ) {
        ArtworkImage(
            url = track.artworkUrl,
            contentDescription = track.title,
            modifier = Modifier.size(width - 8.dp),
            shape = ArtworkShapes.gridTile,
        )
        Spacer(Modifier.height(6.dp))
        Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
        Text(
            track.displayArtist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
