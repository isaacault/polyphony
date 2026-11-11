package app.polyphony.image

import coil3.intercept.Interceptor
import coil3.request.ImageResult

/**
 * On mobile data or a stalling connection ([DataSaver]), ask for smaller artwork: shrink the
 * `size` of `getCoverArt` requests (stored artwork URLs bake in 600 px) and Jellyfin's
 * `maxWidth`/`maxHeight`, and give continuo's catalogue artwork (`/v1/artwork`) a `size`, so the
 * server sends a smaller image. Per request, without disturbing the cache of the full-size variant.
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

        /** Jellyfin's image sizes (`/Items/{id}/Images/Primary?maxWidth=600&maxHeight=600`). */
        private val JELLYFIN_EDGE = Regex("(max(?:Width|Height))=(\\d+)")

        /** The URL with its cover `size` capped at [maxEdge], or null when it is not a shrinkable cover URL. */
        fun reducedUrl(url: String, maxEdge: Int): String? {
            if (url.contains("/Images/")) {
                var changed = false
                val out = JELLYFIN_EDGE.replace(url) { m ->
                    if ((m.groupValues[2].toIntOrNull() ?: 0) > maxEdge) {
                        changed = true
                        "${m.groupValues[1]}=$maxEdge"
                    } else {
                        m.value
                    }
                }
                return out.takeIf { changed }
            }
            if (url.contains("/v1/artwork?") && SIZE.find(url) == null) return "$url&size=$maxEdge"
            if (!url.contains("getCoverArt") && !url.contains("/v1/artwork?")) return null
            val match = SIZE.find(url) ?: return null
            val current = match.groupValues[1].toIntOrNull() ?: return null
            if (current <= maxEdge) return null
            return url.replaceRange(match.range, "size=$maxEdge")
        }
    }
}
