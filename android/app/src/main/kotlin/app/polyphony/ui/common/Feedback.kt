package app.polyphony.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.polyphony.R
import app.polyphony.core.ui.components.StarRating
import app.polyphony.ui.player.FavoriteButton

/**
 * The like control. Favourites mode: the heart that pops on. Ratings mode: a star filled from the
 * left to [rating]/5 whose tap opens a 1–5 picker (tapping the current rating clears it). Only the
 * system in use is shown or written, so the other one is left as it is on the server.
 */
@Composable
fun LikeButton(
    usesRatings: Boolean,
    starred: Boolean,
    rating: Int,
    onToggleFavourite: () -> Unit,
    onRate: (Int) -> Unit,
    modifier: Modifier = Modifier,
    idleTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    if (usesRatings) {
        RatingButton(rating = rating, onRate = onRate, modifier = modifier, idleTint = idleTint)
    } else {
        FavoriteButton(isFavorite = starred, onToggle = onToggleFavourite, modifier = modifier, idleTint = idleTint)
    }
}

@Composable
fun RatingButton(rating: Int, onRate: (Int) -> Unit, modifier: Modifier = Modifier, idleTint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    var open by remember { mutableStateOf(false) }
    val state = if (rating > 0) stringResource(R.string.feedback_rated, rating) else stringResource(R.string.feedback_unrated)
    Box(modifier) {
        IconButton(onClick = { open = true }, modifier = Modifier.semantics { stateDescription = state }) {
            FilledStar(rating = rating, idleTint = idleTint, contentDescription = stringResource(R.string.feedback_rate))
        }
        RatingMenu(expanded = open, rating = rating, onRate = onRate, onDismiss = { open = false })
    }
}

/** The 1–5 picker as a menu under whatever opened it; place it in the same `Box` as that button. */
@Composable
fun RatingMenu(expanded: Boolean, rating: Int, onRate: (Int) -> Unit, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        RatingPicker(rating = rating, onRate = { onRate(it); onDismiss() }, modifier = Modifier.padding(horizontal = 8.dp))
    }
}

/** A star filled from the left to [rating]/5: one glyph that still reads as "how much". */
@Composable
fun FilledStar(rating: Int, modifier: Modifier = Modifier, idleTint: Color = MaterialTheme.colorScheme.onSurfaceVariant, contentDescription: String? = null) {
    val fraction = rating.coerceIn(0, 5) / 5f
    val tint = if (rating > 0) MaterialTheme.colorScheme.primary else idleTint
    Box(modifier) {
        Icon(Icons.Rounded.StarBorder, contentDescription = contentDescription, tint = tint)
        if (fraction > 0f) {
            Icon(
                Icons.Rounded.Star,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.drawWithContent { clipRect(right = size.width * fraction) { this@drawWithContent.drawContent() } },
            )
        }
    }
}

@Composable
private fun RatingPicker(rating: Int, onRate: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StarRating(rating = rating, onRate = onRate)
        if (rating > 0) {
            Text(
                stringResource(R.string.feedback_clear_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The picker as a dialog, for places with no button to anchor a menu to (a swipe). */
@Composable
fun RatingDialog(title: String, rating: Int, onRate: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.feedback_rate_title, title), maxLines = 2) },
        text = { RatingPicker(rating = rating, onRate = { onRate(it); onDismiss() }) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) } },
    )
}
