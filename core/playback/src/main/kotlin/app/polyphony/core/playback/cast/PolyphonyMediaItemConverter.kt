package app.polyphony.core.playback.cast

import android.net.Uri
import android.util.Log
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.transcode.OutputTarget
import app.polyphony.core.playback.engine.ServerLookup
import app.polyphony.core.playback.engine.TranscodeDecisionEngine
import app.polyphony.core.playback.queue.MediaItemFactory
import app.polyphony.core.playback.queue.QueueStore
import app.polyphony.core.playback.util.PlaybackJson
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.common.images.WebImage
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Converts queue items for the Cast receiver. Receivers fetch URLs themselves, so the real stream
 * URL (with the per-device / network transcoding decision) is baked in here. Custom headers cannot
 * be sent by a receiver; providers embed authentication in the URL.
 */
@Singleton
class PolyphonyMediaItemConverter @Inject constructor(
    private val queueStore: QueueStore,
    private val serverLookup: ServerLookup,
    private val engine: TranscodeDecisionEngine,
    private val castManager: CastManager,
) : MediaItemConverter {

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val track = queueStore.get(mediaItem.mediaId) ?: MediaItemFactory.trackFrom(mediaItem)
            ?: error("No track for media item ${mediaItem.mediaId}")
        queueStore.put(track)
        val target = castManager.currentTargetSync() ?: OutputTarget.Local
        val decision = engine.decisionFor(target)
        val provider = serverLookup.providerFor(track.serverId)
        val request = provider?.streamRequest(track, decision.profile)

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, track.title)
            putString(MediaMetadata.KEY_ARTIST, track.displayArtist)
            track.albumName?.let { putString(MediaMetadata.KEY_ALBUM_TITLE, it) }
            (track.albumArtistName ?: track.artistName)?.let { putString(MediaMetadata.KEY_ALBUM_ARTIST, it) }
            track.trackNumber?.let { putInt(MediaMetadata.KEY_TRACK_NUMBER, it) }
            track.discNumber?.let { putInt(MediaMetadata.KEY_DISC_NUMBER, it) }
            val art = provider?.artworkUrl(track.artworkId, 1200) ?: track.artworkUrl
            art?.let { addImage(WebImage(Uri.parse(it))) }
        }
        val customData = JSONObject().apply {
            put(KEY_TRACK_ID, track.id)
            put(KEY_SERVER_ID, track.serverId)
            put(KEY_PROFILE_ID, decision.profile.id)
            put(KEY_TRACK_JSON, PlaybackJson.encodeToString(track))
        }
        val mime = request?.mimeType ?: track.mimeType ?: guessMime(track.container)
        Log.i(
            "PolyphonyCast",
            "Queue item for ${target.key}: \"${track.title}\" ${track.container} -> ${decision.profile.name} (${decision.reason}) " +
                "mime=$mime transcoded=${request?.isTranscoded} host=${request?.url?.let { Uri.parse(it).host }}",
        )
        val info = MediaInfo.Builder(request?.url ?: "")
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(mime)
            .setMetadata(metadata)
            .setStreamDuration(track.durationMs)
            .setCustomData(customData)
            .build()
        return MediaQueueItem.Builder(info).setAutoplay(true).setCustomData(customData).build()
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val data = mediaQueueItem.customData ?: mediaQueueItem.media?.customData
        val track = data?.optString(KEY_TRACK_JSON)?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { PlaybackJson.decodeFromString(Track.serializer(), it) }.getOrNull() }
            ?: data?.optString(KEY_TRACK_ID)?.let(queueStore::get)
        if (track != null) {
            queueStore.put(track)
            return MediaItemFactory.fromTrack(track)
        }
        // Unknown item (e.g. loaded by another sender): expose it as-is so the queue stays consistent.
        val info = mediaQueueItem.media
        return MediaItem.Builder()
            .setMediaId(info?.contentId ?: "unknown")
            .setUri(info?.contentUrl ?: info?.contentId ?: "")
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(info?.metadata?.getString(MediaMetadata.KEY_TITLE))
                    .setArtist(info?.metadata?.getString(MediaMetadata.KEY_ARTIST))
                    .build(),
            )
            .build()
    }

    private fun guessMime(container: String?): String = when (container?.lowercase()) {
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        "m4a", "aac", "mp4" -> "audio/mp4"
        "ogg", "oga", "vorbis" -> "audio/ogg"
        "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "webm" -> "audio/webm"
        else -> "audio/mpeg"
    }

    companion object {
        const val KEY_TRACK_ID = "trackId"
        const val KEY_SERVER_ID = "serverId"
        const val KEY_PROFILE_ID = "profileId"
        const val KEY_TRACK_JSON = "track"
    }
}
