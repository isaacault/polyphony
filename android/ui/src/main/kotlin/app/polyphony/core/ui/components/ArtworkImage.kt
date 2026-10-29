package app.polyphony.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import app.polyphony.core.ui.theme.ArtworkShapes
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade

/**
 * Artwork with a tonal placeholder (never a spinner), clipped to [shape].
 * The signature is shared across features; refine the internals, keep the API.
 */
@Composable
fun ArtworkImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = ArtworkShapes.listThumb,
    placeholder: ImageVector = Icons.Rounded.MusicNote,
    contentScale: ContentScale = ContentScale.Crop,
    crossfade: Boolean = true,
) {
    var failed by remember(url) { mutableStateOf(false) }
    val context = LocalContext.current
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (url == null || failed) {
            Icon(
                imageVector = placeholder,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxSize(0.42f),
            )
        } else {
            AsyncImage(
                model = remember(url, crossfade) {
                    ImageRequest.Builder(context)
                        .data(url)
                        .crossfade(crossfade)
                        .build()
                },
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                onState = { state -> if (state is AsyncImagePainter.State.Error) failed = true },
            )
        }
    }
}
