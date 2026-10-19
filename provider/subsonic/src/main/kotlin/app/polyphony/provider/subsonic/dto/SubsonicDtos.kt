package app.polyphony.provider.subsonic.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class ErrorDto(val code: Int = 0, val message: String? = null)

@Serializable
internal data class ArtistRefDto(val id: String? = null, val name: String = "")

@Serializable
internal data class GenreRefDto(val name: String = "")

@Serializable
internal data class ReplayGainDto(
    val trackGain: Double? = null,
    val albumGain: Double? = null,
    val trackPeak: Double? = null,
    val albumPeak: Double? = null,
    val baseGain: Double? = null,
    val fallbackGain: Double? = null,
)

/** ArtistID3 (plus the OpenSubsonic additions). Also used inside getArtist, where `album` is populated. */
@Serializable
internal data class ArtistDto(
    val id: String,
    val name: String = "",
    val coverArt: String? = null,
    val artistImageUrl: String? = null,
    val albumCount: Int = 0,
    val starred: String? = null,
    val musicBrainzId: String? = null,
    val sortName: String? = null,
    val userRating: Int? = null,
    val album: List<AlbumDto> = emptyList(),
)

/** AlbumID3 (plus OpenSubsonic additions). Inside getAlbum, `song` is populated. */
@Serializable
internal data class AlbumDto(
    val id: String,
    val name: String = "",
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Double = 0.0,
    val playCount: Long? = null,
    val created: String? = null,
    val starred: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val played: String? = null,
    val userRating: Int? = null,
    val musicBrainzId: String? = null,
    val sortName: String? = null,
    val isCompilation: Boolean? = null,
    val displayArtist: String? = null,
    val artists: List<ArtistRefDto>? = null,
    val genres: List<GenreRefDto>? = null,
    val song: List<ChildDto> = emptyList(),
)

/** Child (song / directory entry) plus OpenSubsonic additions. */
@Serializable
internal data class ChildDto(
    val id: String,
    val parent: String? = null,
    val isDir: Boolean = false,
    val title: String = "",
    val album: String? = null,
    val artist: String? = null,
    val track: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val coverArt: String? = null,
    val size: Long? = null,
    val contentType: String? = null,
    val suffix: String? = null,
    val transcodedContentType: String? = null,
    val transcodedSuffix: String? = null,
    val duration: Double? = null,
    val bitRate: Int? = null,
    val path: String? = null,
    val isVideo: Boolean = false,
    val userRating: Int? = null,
    val averageRating: Double? = null,
    val playCount: Long? = null,
    val discNumber: Int? = null,
    val created: String? = null,
    val starred: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val type: String? = null,
    val mediaType: String? = null,
    val bitDepth: Int? = null,
    val samplingRate: Int? = null,
    val channelCount: Int? = null,
    val played: String? = null,
    val bpm: Int? = null,
    val comment: String? = null,
    val sortName: String? = null,
    val musicBrainzId: String? = null,
    val displayArtist: String? = null,
    val displayAlbumArtist: String? = null,
    val artists: List<ArtistRefDto>? = null,
    val albumArtists: List<ArtistRefDto>? = null,
    val genres: List<GenreRefDto>? = null,
    val replayGain: ReplayGainDto? = null,
    val explicitStatus: String? = null,
)

@Serializable
internal data class PlaylistDto(
    val id: String,
    val name: String = "",
    val comment: String? = null,
    val owner: String? = null,
    val public: Boolean = false,
    val songCount: Int = 0,
    val duration: Double = 0.0,
    val created: String? = null,
    val changed: String? = null,
    val coverArt: String? = null,
    val entry: List<ChildDto> = emptyList(),
)

@Serializable
internal data class GenreDto(
    val value: String = "",
    val songCount: Int = 0,
    val albumCount: Int = 0,
)

@Serializable
internal data class IndexDto(val name: String = "", val artist: List<ArtistDto> = emptyList())

@Serializable
internal data class ArtistsDto(val ignoredArticles: String? = null, val index: List<IndexDto> = emptyList())

@Serializable
internal data class AlbumListDto(val album: List<AlbumDto> = emptyList())

@Serializable
internal data class SongListDto(val song: List<ChildDto> = emptyList())

@Serializable
internal data class PlaylistsDto(val playlist: List<PlaylistDto> = emptyList())

@Serializable
internal data class GenresDto(val genre: List<GenreDto> = emptyList())

@Serializable
internal data class StarredDto(
    val artist: List<ArtistDto> = emptyList(),
    val album: List<AlbumDto> = emptyList(),
    val song: List<ChildDto> = emptyList(),
)

@Serializable
internal data class SearchResultDto(
    val artist: List<ArtistDto> = emptyList(),
    val album: List<AlbumDto> = emptyList(),
    val song: List<ChildDto> = emptyList(),
)

@Serializable
internal data class ArtistInfoDto(
    val biography: String? = null,
    val musicBrainzId: String? = null,
    val lastFmUrl: String? = null,
    val smallImageUrl: String? = null,
    val mediumImageUrl: String? = null,
    val largeImageUrl: String? = null,
    val similarArtist: List<ArtistDto> = emptyList(),
)

@Serializable
internal data class LyricsDto(
    val artist: String? = null,
    val title: String? = null,
    val value: String? = null,
)

@Serializable
internal data class LyricLineDto(val start: Long? = null, val value: String = "")

@Serializable
internal data class StructuredLyricsDto(
    val displayArtist: String? = null,
    val displayTitle: String? = null,
    val lang: String? = null,
    val offset: Long = 0,
    val synced: Boolean = false,
    val line: List<LyricLineDto> = emptyList(),
)

@Serializable
internal data class LyricsListDto(val structuredLyrics: List<StructuredLyricsDto> = emptyList())

@Serializable
internal data class ExtensionDto(val name: String = "", val versions: List<Int> = emptyList())
