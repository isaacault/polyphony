package app.polyphony.core.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import app.polyphony.core.ui.theme.ArtworkShapes

/** 2×2 grid built from the first four artwork URLs; falls back to a single image. */
@Composable
fun ArtworkMosaic(
    urls: List<String?>,
    modifier: Modifier = Modifier,
    shape: Shape = ArtworkShapes.gridTile,
) {
    val distinct = urls.filterNotNull().distinct()
    if (distinct.size < 4) {
        ArtworkImage(url = distinct.firstOrNull(), contentDescription = null, modifier = modifier, shape = shape)
        return
    }
    Column(modifier.clip(shape)) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            ArtworkImage(distinct[0], null, Modifier.weight(1f).fillMaxSize(), shape = RectangleShape, crossfade = false)
            ArtworkImage(distinct[1], null, Modifier.weight(1f).fillMaxSize(), shape = RectangleShape, crossfade = false)
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            ArtworkImage(distinct[2], null, Modifier.weight(1f).fillMaxSize(), shape = RectangleShape, crossfade = false)
            ArtworkImage(distinct[3], null, Modifier.weight(1f).fillMaxSize(), shape = RectangleShape, crossfade = false)
        }
    }
}
