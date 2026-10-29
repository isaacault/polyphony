package app.polyphony.core.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Artist(
    val id: String,
    val serverId: String,
    val name: String,
    val sortName: String? = null,
    val albumCount: Int = 0,
    val artworkId: String? = null,
    val artworkUrl: String? = null,
    val starred: Boolean = false,
    val rating: Int = 0,
    val musicBrainzId: String? = null,
    val biography: String? = null,
)

@Serializable
data class Album(
    val id: String,
    val serverId: String,
    val name: String,
    val sortName: String? = null,
    val artistId: String? = null,
    val artistName: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val trackCount: Int = 0,
    val durationMs: Long = 0L,
    val artworkId: String? = null,
    val artworkUrl: String? = null,
    val starred: Boolean = false,
    val rating: Int = 0,
    val playCount: Long = 0L,
    val createdAt: Long = 0L,
    val lastPlayedAt: Long? = null,
    val isCompilation: Boolean = false,
    val musicBrainzId: String? = null,
)

@Serializable
data class Track(
    val id: String,
    val serverId: String,
    val title: String,
    val albumId: String? = null,
    val albumName: String? = null,
    val artistId: String? = null,
    val artistName: String? = null,
    val albumArtistName: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val durationMs: Long = 0L,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
    /** File container / suffix as reported by the server, e.g. "flac", "mp3", "m4a". */
    val container: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val artworkId: String? = null,
    val artworkUrl: String? = null,
    val starred: Boolean = false,
    val rating: Int = 0,
    val playCount: Long = 0L,
    val replayGainTrackDb: Double? = null,
    val replayGainAlbumDb: Double? = null,
    val replayGainTrackPeak: Double? = null,
    val replayGainAlbumPeak: Double? = null,
    val musicBrainzId: String? = null,
    val path: String? = null,
    val createdAt: Long = 0L,
) {
    val displayArtist: String get() = artistName ?: albumArtistName ?: "Unknown artist"
    val isLossless: Boolean
        get() = container?.lowercase() in setOf("flac", "alac", "wav", "aiff", "ape", "wv", "dsf", "dff")
}

@Serializable
data class Playlist(
    val id: String,
    val serverId: String,
    val name: String,
    val description: String? = null,
    val owner: String? = null,
    val trackCount: Int = 0,
    val durationMs: Long = 0L,
    val artworkId: String? = null,
    val artworkUrl: String? = null,
    val isPublic: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@Serializable
data class Genre(
    val name: String,
    val serverId: String,
    val albumCount: Int = 0,
    val trackCount: Int = 0,
)

@Serializable
data class LyricLine(val startMs: Long?, val text: String)

@Serializable
data class Lyrics(
    val lines: List<LyricLine>,
    val synced: Boolean,
    val source: String? = null,
) {
    val plainText: String get() = lines.joinToString("\n") { it.text }
}

data class SearchResults(
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val tracks: List<Track> = emptyList(),
) {
    val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && tracks.isEmpty()
}

data class ArtistDetail(
    val artist: Artist,
    val albums: List<Album>,
    val topTracks: List<Track> = emptyList(),
    val similarArtists: List<Artist> = emptyList(),
)

data class AlbumDetail(val album: Album, val tracks: List<Track>)

data class PlaylistDetail(val playlist: Playlist, val tracks: List<Track>)

data class StarredContent(
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val tracks: List<Track> = emptyList(),
)

enum class AlbumListType {
    RECENTLY_ADDED,
    RECENTLY_PLAYED,
    MOST_PLAYED,
    RANDOM,
    STARRED,
    HIGHEST_RATED,
    ALPHABETICAL_BY_NAME,
    ALPHABETICAL_BY_ARTIST,
    BY_YEAR,
    BY_GENRE,
}

enum class ItemKind { ARTIST, ALBUM, TRACK, PLAYLIST }

/** Optional features a provider implementation may support. UI hides what is absent. */
enum class Capability {
    LYRICS,
    SYNCED_LYRICS,
    RATINGS,
    FAVORITES,
    PLAYLIST_EDIT,
    SCROBBLE,
    SIMILAR_ARTISTS,
    TOP_TRACKS,
    GENRES,
    RANDOM_TRACKS,
    TRANSCODE_FORMAT_SELECTION,
    DOWNLOAD_ORIGINAL,
    FULL_TRACK_LISTING,
}

/** Sort orders used by the local library. */
enum class ArtistSort { NAME, ALBUM_COUNT, RECENTLY_ADDED, STARRED }
enum class AlbumSort { NAME, ARTIST, YEAR, RECENTLY_ADDED, RECENTLY_PLAYED, MOST_PLAYED, RATING, DURATION }
enum class TrackSort { TITLE, ARTIST, ALBUM, DURATION, RECENTLY_ADDED, MOST_PLAYED, RATING }
