package app.polyphony.image

import coil3.intercept.Interceptor
import coil3.request.ImageResult

/**
 * While the data saver is active, shrink the `size` of `getCoverArt` requests so the server sends a
 * smaller image. Stored artwork URLs bake in a 600 px size; this rewrites them per request without
 * disturbing the cache of the full-size variant.
 */
class ArtworkSizeInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val data = chain.request.data
        if (!DataSaver.reduceArtwork || data !is String) return chain.proceed()
        val reduced = reducedUrl(data, DataSaver.MAX_EDGE_PX) ?: return chain.proceed()
        return chain.withRequest(chain.request.newBuilder().data(reduced).build()).proceed()
    }

    companion object {
        private val SIZE = Regex("size=(\\d+)")

        /** The URL with its cover `size` capped at [maxEdge], or null when it is not a shrinkable cover URL. */
        fun reducedUrl(url: String, maxEdge: Int): String? {
            if (!url.contains("getCoverArt")) return null
            val match = SIZE.find(url) ?: return null
            val current = match.groupValues[1].toIntOrNull() ?: return null
            if (current <= maxEdge) return null
            return url.replaceRange(match.range, "size=$maxEdge")
        }
    }
}
