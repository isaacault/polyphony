package app.polyphony.core.ui.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * Navigation transitions. Two kinds only, both quick and without scaling:
 *
 * - **Tab switch** (bottom bar): a short crossfade. Tabs have no direction.
 * - **Push / pop** (everything else): the new screen slides in from the right over the current
 *   one, which drifts left a little and dims; back reverses it, with the leaving screen sliding
 *   fully off to the right. Pop transitions are tween-based so a predictive-back swipe scrubs
 *   them like a real swipe.
 */
object NavMotion {
    private const val TAB_IN = 180
    private const val TAB_OUT = 120
    private const val SLIDE = 240

    /** How far the screen underneath drifts, as a fraction of width (parallax). */
    private const val PARALLAX = 0.25f

    fun tabEnter(): EnterTransition = fadeIn(tween(TAB_IN, easing = Motion.Standard))
    fun tabExit(): ExitTransition = fadeOut(tween(TAB_OUT, easing = Motion.Standard))

    fun pushEnter(): EnterTransition =
        slideInHorizontally(tween(SLIDE, easing = Motion.EmphasizedDecelerate)) { it }

    fun pushExit(): ExitTransition =
        slideOutHorizontally(tween(SLIDE, easing = Motion.EmphasizedDecelerate)) { -(it * PARALLAX).toInt() } +
            fadeOut(tween(SLIDE, easing = Motion.Standard), targetAlpha = 0.6f)

    fun popEnter(): EnterTransition =
        slideInHorizontally(tween(SLIDE, easing = Motion.EmphasizedDecelerate)) { -(it * PARALLAX).toInt() } +
            fadeIn(tween(SLIDE, easing = Motion.Standard), initialAlpha = 0.6f)

    fun popExit(): ExitTransition =
        slideOutHorizontally(tween(SLIDE, easing = Motion.EmphasizedDecelerate)) { it }
}
