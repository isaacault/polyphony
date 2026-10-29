package app.polyphony.core.playback.dlna

import android.util.Log
import androidx.media3.common.MediaItem
import app.polyphony.core.dlna.engine.DlnaMediaResolver
import app.polyphony.core.dlna.engine.ResolvedStream
import app.polyphony.core.dlna.service.RendererFormatSupport
import app.polyphony.core.domain.playback.DlnaRenderer
import app.polyphony.core.playback.engine.ServerLookup
import app.polyphony.core.playback.remote.RemoteUrlResolver
import app.polyphony.core.playback.transcode.CodecSupport
import app.polyphony.core.playback.engine.TranscodeDecisionEngine
import app.polyphony.core.playback.queue.MediaItemFactory
import app.polyphony.core.playback.queue.QueueStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves queue items for a UPnP renderer: applies the transcoding decision for the renderer's
 * [app.polyphony.core.domain.transcode.OutputTarget.Dlna] (per-device override → cast default →
 * network rules), then checks the resulting MIME type against what the renderer advertises and
 * substitutes a supported codec when needed.
 */
@Singleton
class PolyphonyDlnaResolver @Inject constructor(
    private val queueStore: QueueStore,
    private val serverLookup: ServerLookup,
    private val engine: TranscodeDecisionEngine,
    private val remoteUrls: RemoteUrlResolver,
) : DlnaMediaResolver {

    override suspend fun resolve(item: MediaItem, renderer: DlnaRenderer): ResolvedStream? {
        val track = queueStore.get(item.mediaId) ?: MediaItemFactory.trackFrom(item) ?: return null
        queueStore.put(track)
        val provider = serverLookup.providerFor(track.serverId)
            ?: serverLookup.awaitProvider(track.serverId)
            ?: return null
        val decision = engine.decisionFor(renderer.target).let { d ->
            val adjusted = CodecSupport.adjust(d.profile, CodecSupport.supported(provider.server))
            if (adjusted !== d.profile) d.copy(profile = adjusted) else d
        }
        val support = RendererFormatSupport.from(renderer.supportedMimeTypes)
        val choice = DlnaCodecFallback.choose(track, decision.profile, support) { profile ->
            provider.streamRequest(track, profile)
        }
        if (choice.substituted) {
            Log.i(TAG, "${renderer.friendlyName} does not accept ${decision.profile.name}; using ${choice.profile.name} (${choice.mimeType})")
        }
        val server = serverLookup.serverFor(track.serverId)
        fun forReceiver(u: String) = if (server != null) remoteUrls.forReceiver(u, server) else u
        return ResolvedStream(
            url = forReceiver(choice.request.url),
            mimeType = choice.mimeType,
            durationMs = track.durationMs.takeIf { it > 0 },
            title = track.title,
            artist = track.displayArtist,
            album = track.albumName,
            artworkUrl = (provider.artworkUrl(track.artworkId, 1200) ?: track.artworkUrl)?.let(::forReceiver),
            year = track.year,
            trackNumber = track.trackNumber,
        )
    }

    private companion object {
        const val TAG = "PolyphonyDlnaResolver"
    }
}
