package app.polyphony.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polyphony.R
import app.polyphony.core.ui.components.ArtworkImage
import app.polyphony.core.ui.motion.Motion
import app.polyphony.core.ui.theme.ArtworkShapes
import app.polyphony.core.ui.theme.Dimens
import app.polyphony.ui.player.PlayerViewModel
import app.polyphony.ui.player.rememberHaptics

/**
 * Docked mini player: artwork, marquee title, play/pause + next, and a thin progress line on top.
 * Swipe horizontally to skip; tap (or drag up, handled by the sheet) to expand.
 */
@Composable
fun MiniPlayer(
    viewModel: PlayerViewModel,
    onExpand: () -> Unit,
    extraBottom: Dp,
    modifier: Modifier = Modifier,
) {
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val position by viewModel.position.collectAsStateWithLifecycle()
    val hapticsEnabled by viewModel.hapticsEnabled.collectAsStateWithLifecycle()
    val haptics = rememberHaptics(hapticsEnabled)
    val track = playback.currentTrack ?: return

    val density = LocalDensity.current
    val thresholdPx = remember(density) { with(density) { 72.dp.toPx() } }
    var dragX by remember { mutableFloatStateOf(0f) }
    val progressColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val openLabel = stringResource(R.string.mini_player_open)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.miniPlayerHeight + extraBottom)
            .semantics { contentDescription = openLabel }
            .clickable(onClick = onExpand)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    dragX = (dragX + delta).coerceIn(-thresholdPx * 1.6f, thresholdPx * 1.6f)
                },
                onDragStopped = { velocity ->
                    val x = dragX
                    when {
                        x < -thresholdPx || velocity < -1800f -> {
                            if (playback.hasNext) {
                                haptics.gestureEnd()
                                viewModel.next()
                            }
                        }
                        x > thresholdPx || velocity > 1800f -> {
                            haptics.gestureEnd()
                            viewModel.previous()
                        }
                    }
                    animate(x, 0f, animationSpec = Motion.tapSpring()) { value, _ -> dragX = value }
                },
            )
            .drawBehind {
                val lineHeight = 2.dp.toPx()
                drawRect(trackColor, size = Size(size.width, lineHeight))
                drawRect(progressColor, size = Size(size.width * position.progress, lineHeight))
            },
    ) {
        Row(
            modifier = Modifier
                .height(Dimens.miniPlayerHeight)
                .padding(start = 12.dp, end = 4.dp)
                .graphicsLayer { translationX = dragX * 0.55f },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkImage(
                url = track.artworkUrl,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                shape = ArtworkShapes.listThumb,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500),
                )
                Text(
                    text = track.displayArtist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(contentAlignment = Alignment.Center) {
                IconButton(onClick = viewModel::togglePlayPause) {
                    AnimatedContent(
                        targetState = playback.isPlaying,
                        transitionSpec = {
                            (scaleIn(Motion.tapSpring()) + fadeIn()) togetherWith (scaleOut() + fadeOut())
                        },
                        label = "miniPlayPause",
                    ) { playing ->
                        Icon(
                            imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(if (playing) R.string.player_pause else R.string.player_play),
                        )
                    }
                }
                if (playback.isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Transparent,
                    )
                }
            }
            IconButton(onClick = viewModel::next, enabled = playback.hasNext) {
                Icon(Icons.Rounded.SkipNext, contentDescription = stringResource(R.string.player_next))
            }
        }
    }
}
