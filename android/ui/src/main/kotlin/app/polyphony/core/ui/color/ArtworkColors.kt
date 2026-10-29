package app.polyphony.core.ui.color

import android.content.Context
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Colours extracted from a piece of artwork, ready to seed a theme or paint a gradient. */
@Immutable
data class ArtworkColors(
    /** Best single colour to seed a Material scheme from (vibrant, falling back to dominant). */
    val seed: Color,
    val dominant: Color,
    val vibrant: Color,
    val darkVibrant: Color,
    val lightVibrant: Color,
    val muted: Color,
    val darkMuted: Color,
) {
    val isDark: Boolean get() = dominant.luminance() < 0.4f
}

private val colorCache = LruCache<String, ArtworkColors>(96)

/** Extract and cache artwork colours. Decodes a small (128 px) copy; safe to call often. */
suspend fun extractArtworkColors(
    context: Context,
    url: String,
    imageLoader: ImageLoader = SingletonImageLoader.get(context),
): ArtworkColors? {
    colorCache.get(url)?.let { return it }
    return withContext(Dispatchers.Default) {
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(Size(128, 128))
            .allowHardware(false)
            .build()
        val result = imageLoader.execute(request) as? SuccessResult ?: return@withContext null
        val bitmap = result.image.toBitmap()
        val palette = Palette.from(bitmap).maximumColorCount(24).generate()
        val dominant = palette.dominantSwatch?.rgb?.let(::Color) ?: return@withContext null
        val vibrant = palette.vibrantSwatch?.rgb?.let(::Color) ?: dominant
        val colors = ArtworkColors(
            seed = vibrant,
            dominant = dominant,
            vibrant = vibrant,
            darkVibrant = palette.darkVibrantSwatch?.rgb?.let(::Color) ?: vibrant,
            lightVibrant = palette.lightVibrantSwatch?.rgb?.let(::Color) ?: vibrant,
            muted = palette.mutedSwatch?.rgb?.let(::Color) ?: dominant,
            darkMuted = palette.darkMutedSwatch?.rgb?.let(::Color) ?: dominant,
        )
        colorCache.put(url, colors)
        colors
    }
}

/** Composable accessor; returns cached colours synchronously when available. */
@Composable
fun rememberArtworkColors(url: String?): State<ArtworkColors?> {
    val context = LocalContext.current
    return produceState(initialValue = url?.let { colorCache.get(it) }, key1 = url) {
        if (url == null) {
            value = null
        } else {
            value = colorCache.get(url) ?: runCatching { extractArtworkColors(context, url) }.getOrNull()
        }
    }
}
