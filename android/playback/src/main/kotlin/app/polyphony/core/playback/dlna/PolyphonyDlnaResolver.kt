package app.polyphony.core.playback.dlna

import android.util.Log
import androidx.media3.common.MediaItem
import app.polyphony.core.dlna.engine.DlnaMediaResolver
import app.polyphony.core.dlna.engine.ResolvedStream
import app.polyphony.core.dlna.service.RendererFormatSupport
import app.polyphony.core.domain.model.PlayNowIds
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.playback.DlnaRenderer
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.playback.engine.ServerLookup
import app.polyphony.core.playback.remote.RemoteUrlResolver
import app.polyphony.core.playback.transcode.CodecSupport
import app.polyphony.core.playback.engine.TranscodeDecisionEngine
import app.polyphony.core.playback.queue.MediaItemFactory
import app.polyphony.core.playback.queue.QueueStore
import kotlinx.coroutines.CancellationException
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
    private val requests: RequestRepository,
) : DlnaMediaResolver {

    override suspend fun resolve(item: MediaItem, renderer: DlnaRenderer): ResolvedStream? {
        val track = queueStore.get(item.mediaId) ?: MediaItemFactory.trackFrom(item) ?: return null
        queueStore.put(track)
        PlayNowIds.streamId(track.id)?.let { stream -> return resolvePlayNow(track, stream) }
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

    /**
     * A Play now song isn't a library song: there's no Subsonic stream for it. continuo signs a link
     * to its audio that a speaker fetches without a token (continuo#98), on the LAN door; the file
     * as it is (continuo serves it untranscoded).
     */
    private suspend fun resolvePlayNow(track: Track, streamId: String): ResolvedStream? {
        val server = serverLookup.serverFor(track.serverId) ?: return null
        val url = try {
            requests.playNowReceiverUrl(server, streamId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "no speaker link for Play now \"${track.title}\": ${e.message}")
            return null
        }
        return ResolvedStream(
            url = url,
            mimeType = track.mimeType ?: playNowMime(track.container),
            durationMs = track.durationMs.takeIf { it > 0 },
            title = track.title,
            artist = track.displayArtist,
            album = track.albumName,
            artworkUrl = null,
            year = track.year,
            trackNumber = track.trackNumber,
        )
    }

    private companion object {
        const val TAG = "PolyphonyDlnaResolver"

        fun playNowMime(container: String?): String = when (container?.lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a", "aac", "alac" -> "audio/mp4"
            "ogg", "oga" -> "audio/ogg"
            "opus" -> "audio/opus"
            "wav" -> "audio/wav"
            else -> "audio/flac"
        }
    }
}
