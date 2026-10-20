package app.polyphony.ui.browse.home

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.HistoryToggleOff
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.polyphony.R
import app.polyphony.core.domain.model.PlayContext
import app.polyphony.core.domain.model.RecentContext
import app.polyphony.core.ui.components.ArtworkImage
import app.polyphony.core.ui.theme.ArtworkShapes
import app.polyphony.ui.common.OptionsSheet
import app.polyphony.ui.common.SheetHeader
import app.polyphony.ui.common.SheetItem

/** A resumable collection on Home: artwork, its name and the track it will pick up at. */
@Composable
fun ContinueListeningCard(
    entry: RecentContext,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    width: Dp = 160.dp,
) {
    val context = entry.context
    val isArtist = context.kind == PlayContext.Kind.ARTIST
    Column(
        modifier = modifier
            .width(width)
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ArtworkImage(
            url = context.artworkUrl,
            contentDescription = context.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            shape = if (isArtist) CircleShape else ArtworkShapes.gridTile,
            placeholder = context.kind.icon,
        )
        Text(
            text = context.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Row(
            modifier = Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (entry.trackId != null) Icons.Rounded.PlayArrow else context.kind.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(2.dp))
            Text(
                text = entry.trackTitle ?: stringResource(context.kind.label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Long-press options for a "Continue listening" tile. */
@Composable
fun ContinueListeningSheet(
    entry: RecentContext,
    onDismiss: () -> Unit,
    onPlayFromStart: () -> Unit,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = entry.context
    val kindLabel = stringResource(context.kind.label)
    OptionsSheet(
        onDismiss = onDismiss,
        header = {
            SheetHeader(
                artworkUrl = context.artworkUrl,
                title = context.title,
                subtitle = context.subtitle?.let { "$kindLabel · $it" } ?: kindLabel,
            )
        },
    ) {
        SheetItem(Icons.Rounded.Replay, stringResource(R.string.browse_continue_play_from_start)) { onPlayFromStart(); onDismiss() }
        SheetItem(context.kind.icon, stringResource(context.kind.openLabel)) { onOpen(); onDismiss() }
        SheetItem(Icons.Rounded.HistoryToggleOff, stringResource(R.string.browse_continue_remove)) { onRemove(); onDismiss() }
    }
}

private val PlayContext.Kind.icon: ImageVector
    get() = when (this) {
        PlayContext.Kind.ALBUM -> Icons.Rounded.Album
        PlayContext.Kind.ARTIST -> Icons.Rounded.Person
        PlayContext.Kind.PLAYLIST -> Icons.AutoMirrored.Rounded.QueueMusic
        PlayContext.Kind.GENRE -> Icons.Rounded.Category
        PlayContext.Kind.FAVORITES -> Icons.Rounded.Favorite
    }

private val PlayContext.Kind.label: Int
    get() = when (this) {
        PlayContext.Kind.ALBUM -> R.string.browse_kind_album
        PlayContext.Kind.ARTIST -> R.string.browse_kind_artist
        PlayContext.Kind.PLAYLIST -> R.string.browse_kind_playlist
        PlayContext.Kind.GENRE -> R.string.browse_kind_genre
        PlayContext.Kind.FAVORITES -> R.string.browse_kind_favorites
    }

private val PlayContext.Kind.openLabel: Int
    get() = when (this) {
        PlayContext.Kind.ALBUM -> R.string.browse_action_go_to_album
        PlayContext.Kind.ARTIST -> R.string.browse_action_go_to_artist
        PlayContext.Kind.PLAYLIST -> R.string.browse_action_go_to_playlist
        PlayContext.Kind.GENRE -> R.string.browse_action_go_to_genre
        PlayContext.Kind.FAVORITES -> R.string.browse_action_go_to_favorites
    }
