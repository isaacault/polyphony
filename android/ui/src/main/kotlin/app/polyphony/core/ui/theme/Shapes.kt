package app.polyphony.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Generous corner radii everywhere: artwork, cards, sheets. */
val PolyphonyShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

object ArtworkShapes {
    val listThumb = RoundedCornerShape(10.dp)
    val gridTile = RoundedCornerShape(16.dp)
    val hero = RoundedCornerShape(24.dp)
    val player = RoundedCornerShape(28.dp)
}
