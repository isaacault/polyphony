package app.polyphony.ui.browse.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import app.polyphony.core.ui.theme.Dimens
import kotlinx.collections.immutable.ImmutableList
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
import app.polyphony.R
import app.polyphony.core.domain.model.PlayContext
import app.polyphony.core.domain.model.RecentContext
import app.polyphony.core.ui.components.ArtworkImage
import app.polyphony.ui.common.OptionsSheet
import app.polyphony.ui.common.SheetHeader
import app.polyphony.ui.common.SheetItem

/**
 * Spotify-style quick picks: up to six recently played collections as two columns of wide tiles,
 * placed at the top of Home. A tap opens the collection; a long press offers resume, restart and
 * remove.
 */
@Composable
fun ContinueListeningGrid(
    entries: ImmutableList<RecentContext>,
    onClick: (RecentContext) -> Unit,
    onLongClick: (RecentContext) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = entries.take(MAX_QUICK_PICKS).chunked(2)
    Column(
        modifier = modifier.padding(horizontal = Dimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(QUICK_PICK_GAP),
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(QUICK_PICK_GAP)) {
                row.forEach { entry ->
                    ContinueListeningTile(
                        entry = entry,
                        onClick = { onClick(entry) },
                        onLongClick = { onLongClick(entry) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** One quick-pick tile: square artwork flush left, the collection's name beside it. */
@Composable
fun ContinueListeningTile(
    entry: RecentContext,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = entry.context
    val isArtist = context.kind == PlayContext.Kind.ARTIST
    val shape = RoundedCornerShape(QUICK_PICK_RADIUS)
    Row(
        modifier = modifier
            .height(QUICK_PICK_HEIGHT)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(
            url = context.artworkUrl,
            contentDescription = null,
            modifier = if (isArtist) Modifier.padding(4.dp).size(QUICK_PICK_HEIGHT - 8.dp) else Modifier.size(QUICK_PICK_HEIGHT),
            shape = if (isArtist) CircleShape else RoundedCornerShape(topStart = QUICK_PICK_RADIUS, bottomStart = QUICK_PICK_RADIUS),
            placeholder = context.kind.icon,
        )
        Text(
            text = context.title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        )
    }
}

private const val MAX_QUICK_PICKS = 6
private val QUICK_PICK_HEIGHT = 56.dp
private val QUICK_PICK_GAP = 8.dp
private val QUICK_PICK_RADIUS = 6.dp

/** Long-press options for a quick-pick tile. Tapping the tile opens the collection; these resume or forget it. */
@Composable
fun ContinueListeningSheet(
    entry: RecentContext,
    onDismiss: () -> Unit,
    onResume: () -> Unit,
    onPlayFromStart: () -> Unit,
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
        val resumeLabel = entry.trackTitle?.let { stringResource(R.string.browse_continue_resume_track, it) }
            ?: stringResource(R.string.browse_continue_resume)
        SheetItem(Icons.Rounded.PlayArrow, resumeLabel) { onResume(); onDismiss() }
        SheetItem(Icons.Rounded.Replay, stringResource(R.string.browse_continue_play_from_start)) { onPlayFromStart(); onDismiss() }
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

