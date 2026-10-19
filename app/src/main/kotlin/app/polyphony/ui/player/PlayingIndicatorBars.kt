package app.polyphony.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Three bouncing bars; static when paused. Used in the queue to mark the current item. */
@Composable
fun PlayingIndicatorBars(
    playing: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    size: Dp = 18.dp,
) {
    val transition = rememberInfiniteTransition(label = "bars")
    val b1 by transition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse), label = "b1")
    val b2 by transition.animateFloat(0.9f, 0.25f, infiniteRepeatable(tween(430, easing = LinearEasing), RepeatMode.Reverse), label = "b2")
    val b3 by transition.animateFloat(0.5f, 1f, infiniteRepeatable(tween(610, easing = LinearEasing), RepeatMode.Reverse), label = "b3")
    val heights = if (playing) floatArrayOf(b1, b2, b3) else floatArrayOf(0.35f, 0.55f, 0.3f)
    Canvas(modifier.size(size)) {
        val barWidth = this.size.width / 5f
        val gap = barWidth
        heights.forEachIndexed { i, h ->
            val barHeight = this.size.height * h
            drawRoundRect(
                color = color,
                topLeft = Offset(x = i * (barWidth + gap), y = this.size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}
