package app.polyphony.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * A 1–5 star control. Tapping a star sets that rating; tapping the current rating clears it to 0,
 * so a single control both sets and unsets. [rating] is the current value (0 = unrated).
 */
@Composable
fun StarRating(
    rating: Int,
    onRate: (Int) -> Unit,
    modifier: Modifier = Modifier,
    starSize: Int = 28,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier.semantics { stateDescription = "$rating of 5 stars" },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (star in 1..5) {
            val filled = star <= rating
            IconButton(
                onClick = {
                    val next = if (star == rating) 0 else star
                    haptics.performHapticFeedback(if (next == 0) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                    onRate(next)
                },
                modifier = Modifier.size((starSize + 12).dp),
            ) {
                Icon(
                    imageVector = if (filled) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = "Rate $star",
                    tint = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(starSize.dp),
                )
            }
        }
    }
}
