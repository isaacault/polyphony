package app.polyphony.core.ui.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.graphics.TransformOrigin

/**
 * Navigation transitions, one per kind of move, following Material 3 motion:
 *
 * - **Fade through** between peer destinations (bottom-bar tabs): the old screen fades out
 *   quickly, the new one fades and grows in. No direction, because tabs have none.
 * - **Shared axis X** for flows that read as a sequence (settings pages, onboarding steps):
 *   a short 30 dp slide with staggered fades, reversed on back.
 * - **Emerge / recede** for detail screens (album, artist, playlist…): the detail grows out of
 *   the list it came from and shrinks back into it. Because the pop variants are scale + fade,
 *   they also look right while a predictive-back gesture is scrubbing them.
 *
 * All transitions are tween-based so Navigation can seek them for predictive back.
 */
object NavMotion {
    private const val OUT = 90
    private const val IN = 210
    private const val MOVE = 300
    private const val BACK = 260

    // ---- Fade through (top-level tabs) ----
    fun fadeThroughEnter(): EnterTransition =
        fadeIn(tween(IN, delayMillis = OUT, easing = Motion.EmphasizedDecelerate)) +
            scaleIn(tween(IN + OUT, easing = Motion.EmphasizedDecelerate), initialScale = 0.94f)

    fun fadeThroughExit(): ExitTransition =
        fadeOut(tween(OUT, easing = Motion.EmphasizedAccelerate))

    // ---- Shared axis X (settings, onboarding) ----
    fun sharedAxisEnter(offsetPx: Int): EnterTransition =
        slideInHorizontally(tween(MOVE, easing = Motion.EmphasizedDecelerate)) { offsetPx } +
            fadeIn(tween(IN, delayMillis = OUT, easing = Motion.EmphasizedDecelerate))

    fun sharedAxisExit(offsetPx: Int): ExitTransition =
        slideOutHorizontally(tween(MOVE, easing = Motion.EmphasizedAccelerate)) { -offsetPx } +
            fadeOut(tween(OUT, easing = Motion.EmphasizedAccelerate))

    fun sharedAxisPopEnter(offsetPx: Int): EnterTransition =
        slideInHorizontally(tween(MOVE, easing = Motion.EmphasizedDecelerate)) { -offsetPx } +
            fadeIn(tween(IN, delayMillis = OUT, easing = Motion.EmphasizedDecelerate))

    fun sharedAxisPopExit(offsetPx: Int): ExitTransition =
        slideOutHorizontally(tween(MOVE, easing = Motion.EmphasizedAccelerate)) { offsetPx } +
            fadeOut(tween(MOVE, easing = Motion.Standard))

    // ---- Emerge / recede (detail screens) ----
    fun detailEnter(): EnterTransition =
        fadeIn(tween(IN, delayMillis = 60, easing = Motion.EmphasizedDecelerate)) +
            scaleIn(
                tween(MOVE + 60, easing = Motion.EmphasizedDecelerate),
                initialScale = 0.92f,
                transformOrigin = TransformOrigin(0.5f, 0.35f),
            )

    /** The list behind a detail only dims; it must not move or it fights the emerging screen. */
    fun detailExitBehind(): ExitTransition =
        fadeOut(tween(OUT + 60, easing = Motion.EmphasizedAccelerate)) +
            scaleOut(tween(MOVE, easing = Motion.Standard), targetScale = 0.98f)

    fun detailPopExit(): ExitTransition =
        scaleOut(
            tween(BACK, easing = Motion.EmphasizedAccelerate),
            targetScale = 0.9f,
            transformOrigin = TransformOrigin(0.5f, 0.4f),
        ) + fadeOut(tween(BACK, easing = Motion.Standard))

    fun detailPopEnter(): EnterTransition =
        fadeIn(tween(BACK, easing = Motion.EmphasizedDecelerate)) +
            scaleIn(tween(BACK, easing = Motion.EmphasizedDecelerate), initialScale = 0.98f)
}
