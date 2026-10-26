package app.polyphony.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.repository.RatingStyle
import app.polyphony.core.ui.theme.ArtworkShapes
import app.polyphony.core.ui.theme.Dimens
import app.polyphony.core.ui.util.formatDuration

/** How star ratings show on track rows (Appearance → Ratings on songs); provided at the app root. */
val LocalRatingStyle = staticCompositionLocalOf { RatingStyle.COMPACT }

/**
 * A dense track row. Shows artwork (or the track number inside an album) and
 * swaps in [PlayingIndicator] for the current track.
 */
@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    showArtwork: Boolean = true,
    trackNumber: Int? = null,
    subtitle: String? = null,
    /**
     * Without a [subtitle], show the artist (and the album too when there's no artwork to show
     * it); off where the page already says who it's by (an album, an artist's top songs).
     */
    fallbackSubtitle: Boolean = true,
    showQualityBadge: Boolean = true,
    /** The quality chip's text; defaults to the track's own (FLAC, HI-RES). */
    qualityLabel: String? = qualityBadgeLabel(track),
    /** A small heart on a track you've favourited (off where every row is one). */
    showFavourite: Boolean = true,
    /** Your star rating of the track, where set: ★4. */
    showRating: Boolean = true,
    showDuration: Boolean = true,
    /** Shows a small check-circle before the duration when the track is available offline. */
    isDownloaded: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onMoreClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val titleColor = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = Dimens.screenPadding, end = 4.dp, top = 6.dp, bottom = 6.dp)
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(4.dp))
        }
        when {
            showArtwork -> Box(Modifier.size(Dimens.listArtwork), contentAlignment = Alignment.Center) {
                ArtworkImage(
                    url = track.artworkUrl,
                    contentDescription = null,
                    modifier = Modifier.size(Dimens.listArtwork),
                    shape = ArtworkShapes.listThumb,
                )
                if (isCurrent) {
                    Box(
                        Modifier
                            .size(Dimens.listArtwork)
                            .clip(ArtworkShapes.listThumb)
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        PlayingIndicator(color = Color.White, animating = isPlaying)
                    }
                }
            }
            else -> Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                if (isCurrent) {
                    PlayingIndicator(animating = isPlaying)
                } else {
                    Text(
                        text = (trackNumber ?: track.trackNumber)?.toString() ?: "–",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.width(Dimens.itemSpacing))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val badge = qualityLabel?.takeIf { showQualityBadge }
            val line = subtitle ?: if (fallbackSubtitle) defaultSubtitle(track, withAlbum = !showArtwork) else null
            if (badge != null || line != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    badge?.let { QualityBadge(it) }
                    line?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (showFavourite && track.starred) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.Favorite,
                contentDescription = "Favourite",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
        }
        if (showRating && track.rating > 0) {
            Spacer(Modifier.width(8.dp))
            RatingMark(track.rating.coerceIn(1, 5), LocalRatingStyle.current)
        }
        if (isDownloaded) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = "Downloaded",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
        }
        if (showDuration && track.durationMs > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatDuration(track.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (trailing != null) {
            trailing()
        }
        // Options are reached by long-press (see combinedClickable above); no overflow button.
        Spacer(Modifier.width(Dimens.itemSpacing))
    }
}

/** A song's star rating, just before its duration: ★4, or five small stars with [stars] filled. */
@Composable
private fun RatingMark(stars: Int, style: RatingStyle) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "Rated $stars of 5" },
    ) {
        when (style) {
            RatingStyle.COMPACT -> {
                Icon(Icons.Rounded.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                     modifier = Modifier.size(14.dp))
                Text(stars.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            RatingStyle.STARS -> repeat(5) { i ->
                Icon(
                    imageVector = if (i < stars) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = null,
                    tint = if (i < stars) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

@Composable
fun QualityBadge(label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

fun qualityBadgeLabel(track: Track): String? = when {
    track.isLossless && ((track.bitDepth ?: 0) > 16 || (track.sampleRateHz ?: 0) > 48_000) -> "HI-RES"
    track.isLossless -> track.container?.uppercase() ?: "LOSSLESS"
    else -> null
}

// The artwork already says which album it is.
private fun defaultSubtitle(track: Track, withAlbum: Boolean): String {
    val artist = track.displayArtist
    val album = track.albumName
    return if (!withAlbum || album.isNullOrBlank()) artist else "$artist · $album"
}
