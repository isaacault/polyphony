package app.polyphony.core.playback.queue

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.polyphony.core.domain.model.Track
import app.polyphony.core.playback.util.PlaybackJson
import kotlinx.serialization.encodeToString

/**
 * Builds Media3 items for tracks. The URI is opaque (`polyphony://track/{serverId}/{trackId}`);
 * the real stream URL is resolved at load time so quality rules apply per load.
 */
object MediaItemFactory {
    const val SCHEME = "polyphony"
    const val EXTRA_TRACK_ID = "app.polyphony.TRACK_ID"
    const val EXTRA_SERVER_ID = "app.polyphony.SERVER_ID"
    const val EXTRA_TRACK_JSON = "app.polyphony.TRACK_JSON"
    const val EXTRA_ALBUM_ID = "app.polyphony.ALBUM_ID"
    const val EXTRA_ARTIST_ID = "app.polyphony.ARTIST_ID"
    const val EXTRA_STARRED = "app.polyphony.STARRED"
    /** A song you added (Play next, Add to queue): "Next in queue", before the list resumes (UserQueue). */
    const val EXTRA_QUEUED = "app.polyphony.QUEUED"

    fun uriFor(serverId: String, trackId: String): Uri =
        Uri.Builder().scheme(SCHEME).authority("track").appendPath(serverId).appendPath(trackId).build()

    /** Returns (serverId, trackId) for an opaque URI, or null. */
    fun parseUri(uri: Uri): Pair<String, String>? {
        if (uri.scheme != SCHEME || uri.authority != "track") return null
        val segments = uri.pathSegments
        if (segments.size < 2) return null
        return segments[0] to segments[1]
    }

    fun fromTrack(track: Track, artworkUrl: String? = track.artworkUrl, queued: Boolean = false): MediaItem {
        val extras = Bundle().apply {
            putString(EXTRA_TRACK_ID, track.id)
            putString(EXTRA_SERVER_ID, track.serverId)
            putString(EXTRA_TRACK_JSON, PlaybackJson.encodeToString(track))
            track.albumId?.let { putString(EXTRA_ALBUM_ID, it) }
            track.artistId?.let { putString(EXTRA_ARTIST_ID, it) }
            putBoolean(EXTRA_STARRED, track.starred)
            if (queued) putBoolean(EXTRA_QUEUED, true)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.displayArtist)
            .setAlbumTitle(track.albumName)
            .setAlbumArtist(track.albumArtistName ?: track.artistName)
            .setTrackNumber(track.trackNumber)
            .setDiscNumber(track.discNumber)
            .setReleaseYear(track.year)
            .setGenre(track.genre)
            .setDurationMs(track.durationMs.takeIf { it > 0 })
            .setArtworkUri(artworkUrl?.let(Uri::parse))
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(uriFor(track.serverId, track.id))
            .setMimeType(track.mimeType)
            .setMediaMetadata(metadata)
            .build()
    }

    /** Whether the item is one you added (Play next, Add to queue). */
    fun isQueued(item: MediaItem): Boolean = item.mediaMetadata.extras?.getBoolean(EXTRA_QUEUED) == true

    /** Recovers the [Track] embedded in an item's extras, if present. */
    fun trackFrom(item: MediaItem): Track? {
        val json = item.mediaMetadata.extras?.getString(EXTRA_TRACK_JSON) ?: return null
        return runCatching { PlaybackJson.decodeFromString(Track.serializer(), json) }.getOrNull()
    }

    fun serverIdFrom(item: MediaItem): String? =
        item.mediaMetadata.extras?.getString(EXTRA_SERVER_ID)
            ?: item.localConfiguration?.uri?.let { parseUri(it)?.first }

    /** True when the item lost its URI crossing a process boundary and must be rebuilt. */
    fun needsRebuild(item: MediaItem): Boolean = item.localConfiguration == null
}
