package app.polyphony.ui.shell

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.polyphony.core.ui.motion.Motion
import app.polyphony.ui.player.NowPlayingScreen
import app.polyphony.ui.player.PlayerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class PlayerSheetValue { Collapsed, Expanded }

/** What screens can do with the Now Playing sheet. Available via [LocalPlayerSheet]. */
@Stable
interface PlayerSheetController {
    /** 0f = mini player docked, 1f = full screen player. Changes every frame while dragging. */
    val fraction: Float
    /** True when the sheet is expanded or heading there. */
    val isExpanded: Boolean
    fun expand()
    fun collapse()
}

val LocalPlayerSheet = staticCompositionLocalOf<PlayerSheetController> { NoPlayerSheet }

private object NoPlayerSheet : PlayerSheetController {
    override val fraction: Float get() = 0f
    override val isExpanded: Boolean get() = false
    override fun expand() = Unit
    override fun collapse() = Unit
}

@Stable
class PlayerSheetState internal constructor(
    internal val draggable: AnchoredDraggableState<PlayerSheetValue>,
    private val scope: CoroutineScope,
) : PlayerSheetController {

    internal var collapsedOffsetPx by mutableFloatStateOf(0f)

    /** Current vertical offset of the sheet's top edge in px (0 when expanded). */
    val offsetPx: Float
        get() {
            val offset = draggable.offset
            return if (offset.isNaN()) collapsedOffsetPx else offset
        }

    override val fraction: Float
        get() {
            val collapsed = collapsedOffsetPx
            if (collapsed <= 0f) return 0f
            return (1f - offsetPx / collapsed).coerceIn(0f, 1f)
        }

    override val isExpanded: Boolean
        get() = draggable.targetValue == PlayerSheetValue.Expanded

    override fun expand() {
        scope.launch { draggable.animateToValue(PlayerSheetValue.Expanded) }
    }

    override fun collapse() {
        scope.launch { draggable.animateToValue(PlayerSheetValue.Collapsed) }
    }

    suspend fun snapCollapsed() = draggable.snapToValue(PlayerSheetValue.Collapsed)
}

@Composable
fun rememberPlayerSheetState(): PlayerSheetState {
    val scope = rememberCoroutineScope()
    val draggable = remember { AnchoredDraggableState(initialValue = PlayerSheetValue.Collapsed) }
    return remember(draggable, scope) { PlayerSheetState(draggable, scope) }
}

/** Spring-animates the sheet to [target] without relying on a state-level animation spec. */
internal suspend fun <T> AnchoredDraggableState<T>.animateToValue(
    target: T,
    spec: AnimationSpec<Float> = Motion.sheetSpring(),
) {
    anchoredDrag(targetValue = target) { anchors, latestTarget ->
        val targetOffset = anchors.positionOf(latestTarget)
        if (!targetOffset.isNaN()) {
            val start = if (this@animateToValue.offset.isNaN()) targetOffset else this@animateToValue.offset
            var previous = start
            animate(start, targetOffset, animationSpec = spec) { value, velocity ->
                dragTo(value, velocity)
                previous = value
            }
            if (previous != targetOffset) dragTo(targetOffset, 0f)
        }
    }
}

/** Jumps to [target] without animation. */
internal suspend fun <T> AnchoredDraggableState<T>.snapToValue(target: T) {
    anchoredDrag(targetValue = target) { anchors, latestTarget ->
        val targetOffset = anchors.positionOf(latestTarget)
        if (!targetOffset.isNaN()) dragTo(targetOffset, 0f)
    }
}

/** Decide where to settle after a fling that originated inside a nested scrollable. */
internal suspend fun AnchoredDraggableState<PlayerSheetValue>.settleWithVelocity(
    velocity: Float,
    velocityThresholdPx: Float,
) {
    val collapsed = anchors.positionOf(PlayerSheetValue.Collapsed)
    if (collapsed.isNaN()) return
    val current = requireOffset()
    val target = when {
        velocity > velocityThresholdPx -> PlayerSheetValue.Collapsed
        velocity < -velocityThresholdPx -> PlayerSheetValue.Expanded
        current < collapsed * 0.5f -> PlayerSheetValue.Expanded
        else -> PlayerSheetValue.Collapsed
    }
    animateToValue(target)
}

/**
 * Lets vertical lists inside the player (lyrics) hand their overscroll to the sheet: scrolling up
 * expands first, and pulling down past the top collapses.
 */
internal fun playerSheetNestedScrollConnection(
    state: AnchoredDraggableState<PlayerSheetValue>,
    onFling: suspend (velocity: Float) -> Unit,
): NestedScrollConnection = object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val delta = available.y
        return if (delta < 0 && source == NestedScrollSource.UserInput) {
            Offset(0f, state.dispatchRawDelta(delta))
        } else {
            Offset.Zero
        }
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput) Offset(0f, state.dispatchRawDelta(available.y)) else Offset.Zero

    override suspend fun onPreFling(available: Velocity): Velocity {
        val toFling = available.y
        val currentOffset = state.requireOffset()
        val minAnchor = state.anchors.minPosition()
        return if (toFling < 0 && currentOffset > minAnchor) {
            onFling(toFling)
            Velocity(0f, toFling)
        } else {
            Velocity.Zero
        }
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        val atAnchor = state.anchors.positionOf(state.currentValue) == state.requireOffset()
        if (!atAnchor || available.y != 0f) onFling(available.y)
        return available
    }
}

/**
 * The draggable Now Playing surface. Collapsed it shows only the [MiniPlayer]; expanded it covers
 * the screen with [NowPlayingScreen]. The two crossfade as the user drags.
 *
 * @param peekHeight distance from the bottom of the window to the top of the mini player.
 * @param miniExtraBottom space the mini player must paint below its content (navigation bar inset
 *   when the bottom bar is hidden).
 */
@Composable
fun NowPlayingSheet(
    state: PlayerSheetState,
    peekHeight: Dp,
    miniExtraBottom: Dp,
    playerViewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val fullHeightPx = constraints.maxHeight.toFloat()
        val peekPx = with(density) { peekHeight.toPx() }
        val collapsedPx = (fullHeightPx - peekPx).coerceAtLeast(0f)
        val anchors = remember(collapsedPx) {
            DraggableAnchors {
                PlayerSheetValue.Collapsed at collapsedPx
                PlayerSheetValue.Expanded at 0f
            }
        }
        SideEffect {
            state.collapsedOffsetPx = collapsedPx
            if (state.draggable.anchors != anchors) state.draggable.updateAnchors(anchors)
        }

        val velocityThresholdPx = with(density) { 125.dp.toPx() }
        val flingBehavior = AnchoredDraggableDefaults.flingBehavior(
            state = state.draggable,
            positionalThreshold = { distance -> distance * 0.3f },
            animationSpec = Motion.sheetSpring(),
        )
        val interactionSource = remember { MutableInteractionSource() }
        val isDragged by interactionSource.collectIsDraggedAsState()
        val showFull by remember(state) { derivedStateOf { state.fraction > 0.0005f } }
        val showMini by remember(state) { derivedStateOf { state.fraction < 0.5f } }
        val nestedScroll = remember(state, velocityThresholdPx) {
            playerSheetNestedScrollConnection(state.draggable) { velocity ->
                state.draggable.settleWithVelocity(velocity, velocityThresholdPx)
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(0, state.offsetPx.roundToInt()) },
        ) {
            if (showFull || isDragged) {
                NowPlayingScreen(
                    viewModel = playerViewModel,
                    sheet = state,
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(nestedScroll)
                        .anchoredDraggable(
                            state = state.draggable,
                            orientation = Orientation.Vertical,
                            interactionSource = interactionSource,
                            flingBehavior = flingBehavior,
                        )
                        .graphicsLayer { alpha = ((state.fraction - 0.06f) / 0.3f).coerceIn(0f, 1f) },
                )
            }
            if (showMini) {
                MiniPlayer(
                    viewModel = playerViewModel,
                    onExpand = state::expand,
                    extraBottom = miniExtraBottom,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .anchoredDraggable(
                            state = state.draggable,
                            orientation = Orientation.Vertical,
                            interactionSource = interactionSource,
                            flingBehavior = flingBehavior,
                        )
                        .graphicsLayer { alpha = 1f - (state.fraction / 0.25f).coerceIn(0f, 1f) },
                )
            }
        }
    }
}
