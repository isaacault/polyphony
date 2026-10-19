package app.polyphony.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.polyphony.R
import app.polyphony.core.domain.playback.RepeatMode
import app.polyphony.core.domain.transcode.TranscodeDecision
import app.polyphony.core.ui.motion.Motion
import app.polyphony.core.ui.theme.Dimens

/** shuffle · previous · play/pause · next · repeat */
@Composable
fun PlayerControls(
    isPlaying: Boolean,
    isBuffering: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onShuffle, modifier = Modifier.size(Dimens.playerSecondaryControlSize)) {
            Icon(
                imageVector = Icons.Rounded.Shuffle,
                contentDescription = stringResource(R.string.player_shuffle),
                tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onPrevious, enabled = hasPrevious || true, modifier = Modifier.size(60.dp)) {
            Icon(
                imageVector = Icons.Rounded.SkipPrevious,
                contentDescription = stringResource(R.string.player_previous),
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        PlayPauseButton(isPlaying = isPlaying, isBuffering = isBuffering, onClick = onPlayPause)
        IconButton(onClick = onNext, enabled = hasNext, modifier = Modifier.size(60.dp)) {
            Icon(
                imageVector = Icons.Rounded.SkipNext,
                contentDescription = stringResource(R.string.player_next),
                modifier = Modifier.size(40.dp),
                tint = if (hasNext) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
        }
        IconButton(onClick = onRepeat, modifier = Modifier.size(Dimens.playerSecondaryControlSize)) {
            val (icon, label) = when (repeatMode) {
                RepeatMode.OFF -> Icons.Rounded.Repeat to R.string.player_repeat_off
                RepeatMode.ALL -> Icons.Rounded.Repeat to R.string.player_repeat_all
                RepeatMode.ONE -> Icons.Rounded.RepeatOne to R.string.player_repeat_one
            }
            Icon(
                imageVector = icon,
                contentDescription = stringResource(label),
                tint = if (repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Large tonal button that morphs between a circle (paused) and a rounded square (playing). */
@Composable
fun PlayPauseButton(
    isPlaying: Boolean,
    isBuffering: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val radius by animateDpAsState(
        targetValue = if (isPlaying) 22.dp else Dimens.playerControlSize / 2,
        animationSpec = Motion.tapSpring(),
        label = "playShape",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(Dimens.playerControlSize),
            shape = RoundedCornerShape(radius),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            AnimatedContent(
                targetState = isPlaying,
                transitionSpec = {
                    (scaleIn(Motion.tapSpring()) + fadeIn()) togetherWith (scaleOut() + fadeOut())
                },
                label = "playPauseIcon",
            ) { playing ->
                Icon(
                    imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(if (playing) R.string.player_pause else R.string.player_play),
                    modifier = Modifier.size(38.dp),
                )
            }
        }
        if (isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier.size(Dimens.playerControlSize + 10.dp),
                strokeWidth = 2.5.dp,
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
            )
        }
    }
}

/** Heart that pops when it turns on. */
@Composable
fun FavoriteButton(
    isFavorite: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(isFavorite) {
        if (isFavorite) {
            scale.animateTo(1.35f, Motion.tapSpring())
            scale.animateTo(1f, Motion.tapSpring())
        }
    }
    IconButton(onClick = onToggle, modifier = modifier) {
        Icon(
            imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            contentDescription = stringResource(if (isFavorite) R.string.player_favorite_remove else R.string.player_favorite_add),
            tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            },
        )
    }
}

/**
 * Shows the active transcoding decision. Turns into a warning chip while latency capping is on;
 * tapping it then restores quality. Long-press opens Streaming settings.
 */
@Composable
fun QualityChip(
    decision: TranscodeDecision?,
    latencyDegraded: Boolean,
    onRestore: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val downloaded = decision?.reason == TranscodeDecision.Reason.DOWNLOADED
    val container = when {
        latencyDegraded && !downloaded -> MaterialTheme.colorScheme.errorContainer
        downloaded -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val content = when {
        latencyDegraded && !downloaded -> MaterialTheme.colorScheme.onErrorContainer
        downloaded -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    val text = when {
        downloaded && decision != null -> decision.chipText()
        latencyDegraded -> stringResource(R.string.player_quality_reduced)
        decision != null -> decision.chipText()
        else -> stringResource(R.string.player_quality_original)
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = content,
        modifier = modifier.combinedClickable(
            onClick = { if (latencyDegraded && !downloaded) onRestore() else onOpenSettings() },
            onLongClick = onOpenSettings,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = when {
                    downloaded -> Icons.Rounded.DownloadDone
                    latencyDegraded -> Icons.Rounded.WarningAmber
                    else -> Icons.Rounded.GraphicEq
                },
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
