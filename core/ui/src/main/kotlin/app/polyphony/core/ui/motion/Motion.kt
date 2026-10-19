package app.polyphony.core.ui.motion

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset

/**
 * Motion tokens shared by every screen. Material 3 "emphasized" easing for screen-level moves,
 * springs for touch-driven things (sheets, drag handles, like buttons).
 */
object Motion {
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
    val Standard = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    const val DurationShort = 150
    const val DurationMedium = 300
    const val DurationLong = 450

    /** Fade-through: the Material pattern for switching between top-level destinations. */
    fun fadeThroughEnter(): EnterTransition =
        fadeIn(tween(DurationMedium, delayMillis = 60, easing = EmphasizedDecelerate)) +
            scaleIn(tween(DurationMedium, delayMillis = 60, easing = EmphasizedDecelerate), initialScale = 0.96f)

    fun fadeThroughExit(): ExitTransition = fadeOut(tween(DurationShort, easing = EmphasizedAccelerate))

    /** Shared-axis X: used when drilling into a detail screen. */
    fun AnimatedContentTransitionScope<*>.sharedAxisEnter(): EnterTransition =
        slideInHorizontally(tween(DurationLong, easing = EmphasizedDecelerate)) { (it * 0.12f).toInt() } +
            fadeIn(tween(DurationMedium, easing = EmphasizedDecelerate))

    fun AnimatedContentTransitionScope<*>.sharedAxisExit(): ExitTransition =
        slideOutHorizontally(tween(DurationMedium, easing = EmphasizedAccelerate)) { -(it * 0.08f).toInt() } +
            fadeOut(tween(DurationShort, easing = EmphasizedAccelerate))

    fun AnimatedContentTransitionScope<*>.sharedAxisPopEnter(): EnterTransition =
        slideInHorizontally(tween(DurationLong, easing = EmphasizedDecelerate)) { -(it * 0.08f).toInt() } +
            fadeIn(tween(DurationMedium, easing = EmphasizedDecelerate))

    fun AnimatedContentTransitionScope<*>.sharedAxisPopExit(): ExitTransition =
        slideOutHorizontally(tween(DurationMedium, easing = EmphasizedAccelerate)) { (it * 0.12f).toInt() } +
            fadeOut(tween(DurationShort, easing = EmphasizedAccelerate))

    /** Bouncy-but-settled spring for sheets and draggable surfaces. */
    fun <T> sheetSpring() = spring<T>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)

    /** Snappy spring for small interactive affordances (like/favourite, play button morph). */
    fun <T> tapSpring() = spring<T>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)

    val offsetSpring = spring<IntOffset>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
}
