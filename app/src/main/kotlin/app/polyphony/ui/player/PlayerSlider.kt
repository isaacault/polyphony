package app.polyphony.ui.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import app.polyphony.core.domain.playback.PlaybackPosition
import app.polyphony.core.ui.motion.Motion
import app.polyphony.core.ui.util.formatDuration
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Thin seek bar with a buffered segment. While dragging, the labels follow the finger; the actual
 * seek happens on release and the bar holds the target until the player catches up.
 */
@Composable
fun PlayerSlider(
    position: PlaybackPosition,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val duration = position.durationMs.coerceAtLeast(0L)
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    var pendingSeekMs by remember { mutableStateOf<Long?>(null) }
    var showRemaining by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(position.positionMs, pendingSeekMs) {
        val pending = pendingSeekMs ?: return@LaunchedEffect
        if (abs(position.positionMs - pending) < 1_200L) pendingSeekMs = null
    }
    LaunchedEffect(pendingSeekMs) {
        if (pendingSeekMs != null) {
            delay(2_000)
            pendingSeekMs = null
        }
    }

    val displayFraction = scrubFraction
        ?: pendingSeekMs?.let { if (duration > 0) (it.toFloat() / duration).coerceIn(0f, 1f) else 0f }
        ?: position.progress
    val displayPositionMs = (displayFraction * duration).toLong()

    Column(modifier) {
        SeekBar(
            fraction = displayFraction,
            buffered = position.bufferedProgress,
            enabled = duration > 0,
            scrubbing = scrubFraction != null,
            onScrub = { scrubFraction = it },
            onScrubEnd = { fraction ->
                if (duration > 0) {
                    val target = (fraction * duration).toLong()
                    pendingSeekMs = target
                    onSeek(target)
                }
                scrubFraction = null
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatDuration(displayPositionMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (showRemaining) "-" + formatDuration((duration - displayPositionMs).coerceAtLeast(0L))
                else formatDuration(duration),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { showRemaining = !showRemaining },
            )
        }
    }
}

/** Custom slim track + thumb; tap or drag anywhere on the 32 dp hit area. */
@Composable
private fun SeekBar(
    fraction: Float,
    buffered: Float,
    enabled: Boolean,
    scrubbing: Boolean,
    onScrub: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val bufferedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    val inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val trackHeight by animateDpAsState(if (scrubbing) 7.dp else 4.dp, Motion.tapSpring(), label = "trackHeight")
    val thumbSize by animateDpAsState(if (scrubbing) 18.dp else 12.dp, Motion.tapSpring(), label = "thumb")
    val latestOnScrub by rememberUpdatedState(onScrub)
    val latestOnScrubEnd by rememberUpdatedState(onScrubEnd)
    var lastFraction by remember { mutableFloatStateOf(fraction) }
    val hitHeight = 32.dp
    val thumbRadiusMax = 9.dp

    Canvas(
        modifier
            .height(hitHeight)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                if (enabled) {
                    setProgress { target ->
                        latestOnScrubEnd(target.coerceIn(0f, 1f))
                        true
                    }
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val inset = thumbRadiusMax.toPx()
                fun fractionAt(x: Float): Float = ((x - inset) / (size.width - 2 * inset)).coerceIn(0f, 1f)
                detectTapGestures(
                    onPress = { offset ->
                        val f = fractionAt(offset.x)
                        lastFraction = f
                        latestOnScrub(f)
                        val released = tryAwaitRelease()
                        if (released) latestOnScrubEnd(f) else latestOnScrubEnd(lastFraction)
                    },
                )
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val inset = thumbRadiusMax.toPx()
                fun fractionAt(x: Float): Float = ((x - inset) / (size.width - 2 * inset)).coerceIn(0f, 1f)
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        lastFraction = fractionAt(offset.x)
                        latestOnScrub(lastFraction)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        lastFraction = fractionAt(change.position.x)
                        latestOnScrub(lastFraction)
                    },
                    onDragEnd = { latestOnScrubEnd(lastFraction) },
                    onDragCancel = { latestOnScrubEnd(lastFraction) },
                )
            },
    ) {
        val inset = thumbRadiusMax.toPx()
        val trackWidth = size.width - 2 * inset
        val h = trackHeight.toPx()
        val top = (size.height - h) / 2f
        val radius = CornerRadius(h / 2f)
        drawRoundRect(inactiveColor, topLeft = Offset(inset, top), size = Size(trackWidth, h), cornerRadius = radius)
        drawRoundRect(
            bufferedColor,
            topLeft = Offset(inset, top),
            size = Size(trackWidth * buffered.coerceIn(0f, 1f), h),
            cornerRadius = radius,
        )
        val activeWidth = trackWidth * fraction.coerceIn(0f, 1f)
        drawRoundRect(activeColor, topLeft = Offset(inset, top), size = Size(activeWidth, h), cornerRadius = radius)
        drawCircle(
            color = activeColor,
            radius = thumbSize.toPx() / 2f,
            center = Offset(inset + activeWidth, size.height / 2f),
        )
    }
}
