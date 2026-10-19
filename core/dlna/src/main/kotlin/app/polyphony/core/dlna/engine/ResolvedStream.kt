package app.polyphony.core.dlna.engine

import androidx.media3.common.MediaItem
import app.polyphony.core.domain.playback.DlnaRenderer

/** Everything the renderer needs to fetch and describe one track. */
data class ResolvedStream(
    val url: String,
    val mimeType: String,
    val durationMs: Long? = null,
    val title: String = "",
    val artist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
)

/**
 * Implemented by the playback module: turns a queue item into a URL the renderer can fetch,
 * honouring the transcoding decision for `OutputTarget.Dlna(renderer.udn, …)`.
 * Use [app.polyphony.core.dlna.service.RendererFormatSupport.from] on
 * [DlnaRenderer.supportedMimeTypes] to sanity-check the chosen codec against the renderer.
 */
fun interface DlnaMediaResolver {
    suspend fun resolve(item: MediaItem, renderer: DlnaRenderer): ResolvedStream?
}
