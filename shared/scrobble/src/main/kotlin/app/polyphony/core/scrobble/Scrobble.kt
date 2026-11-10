package app.polyphony.core.scrobble

import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Artist
import app.polyphony.core.domain.model.MusicBrainzIds
import app.polyphony.core.domain.model.Track
import kotlinx.serialization.Serializable

/**
 * One listen, as both services take it. Kept in the offline queue as JSON, so a field added later
 * needs a default. The MusicBrainz ids are the server's: the track's (Navidrome's recording id), its
 * album's (release) and its artist's, each only when well-formed.
 */
@Serializable
data class Scrobble(
    val artist: String,
    val track: String,
    val listenedAtMs: Long,
    val album: String? = null,
    val albumArtist: String? = null,
    val durationMs: Long = 0L,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val recordingMbid: String? = null,
    val releaseMbid: String? = null,
    val artistMbids: List<String> = emptyList(),
) {
    val listenedAtSeconds: Long get() = listenedAtMs / 1000

    companion object {
        /**
         * The listen for [track] at [listenedAtMs]; [album] and [artist] are the library's rows for
         * it, when it has them, for their MusicBrainz ids. Null without a title or an artist name:
         * neither service takes an anonymous listen.
         */
        fun of(track: Track, listenedAtMs: Long, album: Album? = null, artist: Artist? = null): Scrobble? {
            val title = track.title.trim().takeIf { it.isNotEmpty() } ?: return null
            val artistName = (track.artistName ?: track.albumArtistName)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val artistMbid = artist?.takeIf { it.id == track.artistId }?.musicBrainzId?.let(MusicBrainzIds::first)
            return Scrobble(
                artist = artistName,
                track = title,
                listenedAtMs = listenedAtMs,
                album = track.albumName?.trim()?.takeIf { it.isNotEmpty() },
                albumArtist = track.albumArtistName?.trim()?.takeIf { it.isNotEmpty() && it != artistName },
                durationMs = track.durationMs.coerceAtLeast(0L),
                trackNumber = track.trackNumber?.takeIf { it > 0 },
                discNumber = track.discNumber?.takeIf { it > 0 },
                recordingMbid = MusicBrainzIds.first(track.musicBrainzId),
                releaseMbid = album?.takeIf { it.id == track.albumId }?.musicBrainzId?.let(MusicBrainzIds::first),
                artistMbids = listOfNotNull(artistMbid),
            )
        }
    }
}
