package app.polyphony.core.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity

/**
 * A placeholder's fill with a soft highlight sweeping across it, as Spotify's loading skeletons
 * do: [base] with a lighter band moving left to right every [SWEEP_MS]. The band is sized to
 * the screen's width and every placeholder sweeps in step, so a page of them reads as one shimmer.
 */
fun Modifier.shimmer(base: Color): Modifier = composed {
    val widthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.toFloat() * density }
    val progress by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SWEEP_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer-sweep",
    )
    val highlight = lerp(base, Color.White, 0.08f)
    val band = widthPx * 0.6f
    val x = -band + (widthPx + 2 * band) * progress
    background(Brush.linearGradient(listOf(base, highlight, base), start = Offset(x - band, 0f), end = Offset(x + band, 0f)))
}

private const val SWEEP_MS = 1_300
