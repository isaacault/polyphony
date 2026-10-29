package app.polyphony.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.polyphony.core.ui.motion.Motion
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** How one swipe direction is drawn behind the row while the finger drags it. */
@Immutable
class SwipeActionSpec(
    val icon: ImageVector,
    val label: String,
    val containerColor: Color,
    val contentColor: Color,
)

/** Physical direction of a swipe, independent of layout direction. */
enum class SwipeDirection { LEFT_TO_RIGHT, RIGHT_TO_LEFT }

/**
 * Wraps a list row with "swipe past the threshold and let go" actions, one per direction.
 *
 * Only directions with a spec take part in the gesture: a drag the other way is left unconsumed so
 * a parent pager, or another swipe container, still receives it. The row follows the finger with a
 * rubber band past the threshold, so it never flies off screen, and springs home on release.
 * [onThresholdCrossed] fires once each time the drag enters the commit zone (hook a haptic there).
 *
 * The content is drawn on [containerColor] because the action surface sits underneath it.
 */
@Composable
fun SwipeActionRow(
    leftToRight: SwipeActionSpec?,
    rightToLeft: SwipeActionSpec?,
    onLeftToRight: () -> Unit,
    onRightToLeft: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    onThresholdCrossed: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    if (!enabled || (leftToRight == null && rightToLeft == null)) {
        Box(modifier) { content() }
        return
    }

    val scope = rememberCoroutineScope()
    val maxThresholdPx = with(LocalDensity.current) { 112.dp.toPx() }
    val currentLeftToRight by rememberUpdatedState(onLeftToRight)
    val currentRightToLeft by rememberUpdatedState(onRightToLeft)
    val currentThresholdCrossed by rememberUpdatedState(onThresholdCrossed)

    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var direction by remember { mutableStateOf<SwipeDirection?>(null) }
    var crossed by remember { mutableStateOf(false) }
    val settle = remember { Animatable(0f) }

    val hasLeftToRight = leftToRight != null
    val hasRightToLeft = rightToLeft != null

    val gesture = Modifier.pointerInput(hasLeftToRight, hasRightToLeft, maxThresholdPx) {
        val velocityTracker = VelocityTracker()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var active: SwipeDirection? = null
            var raw = 0f
            val threshold = SwipeActionMath.thresholdPx(size.width.toFloat(), maxThresholdPx)

            val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                val dir = if (over > 0f) SwipeDirection.LEFT_TO_RIGHT else SwipeDirection.RIGHT_TO_LEFT
                val handled = (dir == SwipeDirection.LEFT_TO_RIGHT && hasLeftToRight) ||
                    (dir == SwipeDirection.RIGHT_TO_LEFT && hasRightToLeft)
                if (handled) {
                    active = dir
                    raw = over
                    change.consume()
                }
            }
            val dir = active
            // Either cancelled (vertical scroll won) or a direction with no action: leave it to parents.
            if (slop == null || dir == null) return@awaitEachGesture

            velocityTracker.resetTracking()
            velocityTracker.addPointerInputChange(down)
            velocityTracker.addPointerInputChange(slop)
            dragging = true
            direction = dir
            crossed = false
            dragOffset = SwipeActionMath.dampedOffset(raw, threshold)

            val completed = horizontalDrag(slop.id) { change ->
                velocityTracker.addPointerInputChange(change)
                raw += change.positionChange().x
                // The gesture stays in the direction it started; the other way is clamped at home.
                raw = if (dir == SwipeDirection.LEFT_TO_RIGHT) raw.coerceAtLeast(0f) else raw.coerceAtMost(0f)
                change.consume()
                dragOffset = SwipeActionMath.dampedOffset(raw, threshold)
                val nowCrossed = abs(raw) >= threshold
                if (nowCrossed != crossed) {
                    crossed = nowCrossed
                    if (nowCrossed) currentThresholdCrossed()
                }
            }

            val velocity = velocityTracker.calculateVelocity().x
            if (completed && SwipeActionMath.shouldCommit(raw, threshold, velocity)) {
                when (dir) {
                    SwipeDirection.LEFT_TO_RIGHT -> currentLeftToRight()
                    SwipeDirection.RIGHT_TO_LEFT -> currentRightToLeft()
                }
            }
            val released = dragOffset
            scope.launch {
                // A newer gesture's snapTo cancels this animation, so the reset below only runs
                // when nothing else has taken over the row.
                settle.snapTo(released)
                dragging = false
                settle.animateTo(0f, Motion.sheetSpring())
                direction = null
                crossed = false
            }
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .clipToBounds()
            .then(gesture),
    ) {
        val activeDirection = direction
        val spec = when (activeDirection) {
            SwipeDirection.LEFT_TO_RIGHT -> leftToRight
            SwipeDirection.RIGHT_TO_LEFT -> rightToLeft
            null -> null
        }
        if (spec != null && activeDirection != null) {
            SwipeActionSurface(
                spec = spec,
                direction = activeDirection,
                crossed = crossed,
                offset = { if (dragging) dragOffset else settle.value },
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .absoluteOffset { IntOffset((if (dragging) dragOffset else settle.value).roundToInt(), 0) }
                .background(containerColor),
        ) {
            content()
        }
    }
}

@Composable
private fun BoxScope.SwipeActionSurface(
    spec: SwipeActionSpec,
    direction: SwipeDirection,
    crossed: Boolean,
    offset: () -> Float,
) {
    val neutral = MaterialTheme.colorScheme.surfaceContainerHighest
    val neutralContent = MaterialTheme.colorScheme.onSurfaceVariant
    val background by animateColorAsState(if (crossed) spec.containerColor else neutral, label = "swipeBg")
    val foreground by animateColorAsState(if (crossed) spec.contentColor else neutralContent, label = "swipeFg")
    val scale by animateFloatAsState(if (crossed) 1f else 0.82f, animationSpec = Motion.tapSpring(), label = "swipeScale")
    val alignment = if (direction == SwipeDirection.LEFT_TO_RIGHT) AbsoluteAlignment.CenterLeft else AbsoluteAlignment.CenterRight

    Box(
        Modifier
            .matchParentSize()
            .background(background),
        contentAlignment = alignment,
    ) {
        Row(
            Modifier
                .padding(horizontal = 24.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = (abs(offset()) / 48.dp.toPx()).coerceIn(0f, 1f)
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (direction == SwipeDirection.RIGHT_TO_LEFT) {
                Text(spec.label, style = MaterialTheme.typography.labelLarge, color = foreground, maxLines = 1)
                Spacer(Modifier.width(12.dp))
            }
            Icon(spec.icon, contentDescription = null, tint = foreground)
            if (direction == SwipeDirection.LEFT_TO_RIGHT) {
                Spacer(Modifier.width(12.dp))
                Text(spec.label, style = MaterialTheme.typography.labelLarge, color = foreground, maxLines = 1)
            }
        }
    }
}
