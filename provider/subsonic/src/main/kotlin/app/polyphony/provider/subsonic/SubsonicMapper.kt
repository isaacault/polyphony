package app.polyphony.provider.subsonic

import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.AlbumListType
import app.polyphony.core.domain.model.Artist
import app.polyphony.core.domain.model.Genre
import app.polyphony.core.domain.model.LyricLine
import app.polyphony.core.domain.model.Lyrics
import app.polyphony.core.domain.model.MusicBrainzIds
import app.polyphony.core.domain.model.Playlist
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.transcode.AudioCodec
import app.polyphony.provider.subsonic.api.SubsonicUrlBuilder
import app.polyphony.provider.subsonic.dto.AlbumDto
import app.polyphony.provider.subsonic.dto.ArtistDto
import app.polyphony.provider.subsonic.dto.ChildDto
import app.polyphony.provider.subsonic.dto.GenreDto
import app.polyphony.provider.subsonic.dto.LyricsDto
import app.polyphony.provider.subsonic.dto.PlaylistDto
import app.polyphony.provider.subsonic.dto.StructuredLyricsDto
import app.polyphony.provider.subsonic.internal.parseIsoMillis

/** DTO → domain conversions. Pure; safe to unit test without a server. */
internal class SubsonicMapper(
    private val serverId: String,
    private val urls: SubsonicUrlBuilder,
) {
    fun artworkUrl(artworkId: String?, sizePx: Int): String? {
        val id = artworkId?.takeIf { it.isNotBlank() } ?: return null
        return urls.buildString("getCoverArt", mapOf("id" to id, "size" to sizePx))
    }

    fun artist(dto: ArtistDto): Artist = Artist(
        id = dto.id,
        serverId = serverId,
        name = dto.name,
        sortName = dto.sortName,
        albumCount = dto.albumCount,
        artworkId = dto.coverArt,
        artworkUrl = artworkUrl(dto.coverArt, DEFAULT_ART_SIZE) ?: dto.artistImageUrl?.takeIf { it.isNotBlank() },
        starred = dto.starred != null,
        rating = dto.userRating ?: 0,
        musicBrainzId = MusicBrainzIds.first(dto.musicBrainzId),
    )

    fun album(dto: AlbumDto): Album = Album(
        id = dto.id,
        serverId = serverId,
        name = dto.name,
        sortName = dto.sortName,
        artistId = dto.artistId ?: dto.artists?.firstOrNull()?.id,
        artistName = dto.displayArtist ?: dto.artist ?: dto.artists?.joinToString(", ") { it.name },
        year = dto.year?.takeIf { it > 0 },
        genre = dto.genre ?: dto.genres?.firstOrNull()?.name,
        trackCount = dto.songCount,
        durationMs = (dto.duration * 1000).toLong(),
        artworkId = dto.coverArt,
        artworkUrl = artworkUrl(dto.coverArt, DEFAULT_ART_SIZE),
        starred = dto.starred != null,
        rating = dto.userRating ?: 0,
        playCount = dto.playCount ?: 0L,
        createdAt = parseIsoMillis(dto.created) ?: 0L,
        lastPlayedAt = parseIsoMillis(dto.played),
        isCompilation = dto.isCompilation ?: false,
        musicBrainzId = MusicBrainzIds.first(dto.musicBrainzId),
    )

    fun track(dto: ChildDto): Track {
        val artworkId = dto.coverArt ?: dto.albumId
        return Track(
            id = dto.id,
            serverId = serverId,
            title = dto.title,
            albumId = dto.albumId,
            albumName = dto.album,
            artistId = dto.artistId ?: dto.artists?.firstOrNull()?.id,
            artistName = dto.displayArtist ?: dto.artist ?: dto.artists?.joinToString(", ") { it.name },
            albumArtistName = dto.displayAlbumArtist ?: dto.albumArtists?.joinToString(", ") { it.name },
            trackNumber = dto.track,
            discNumber = dto.discNumber,
            year = dto.year?.takeIf { it > 0 },
            genre = dto.genre ?: dto.genres?.firstOrNull()?.name,
            durationMs = ((dto.duration ?: 0.0) * 1000).toLong(),
            bitrateKbps = dto.bitRate?.takeIf { it > 0 },
            sampleRateHz = dto.samplingRate?.takeIf { it > 0 },
            bitDepth = dto.bitDepth?.takeIf { it > 0 },
            channels = dto.channelCount?.takeIf { it > 0 },
            container = dto.suffix?.lowercase(),
            mimeType = dto.contentType,
            sizeBytes = dto.size,
            artworkId = artworkId,
            artworkUrl = artworkUrl(artworkId, DEFAULT_ART_SIZE),
            starred = dto.starred != null,
            rating = dto.userRating ?: 0,
            playCount = dto.playCount ?: 0L,
            replayGainTrackDb = dto.replayGain?.trackGain,
            replayGainAlbumDb = dto.replayGain?.albumGain,
            replayGainTrackPeak = dto.replayGain?.trackPeak,
            replayGainAlbumPeak = dto.replayGain?.albumPeak,
            musicBrainzId = MusicBrainzIds.first(dto.musicBrainzId),
            path = dto.path,
            createdAt = parseIsoMillis(dto.created) ?: 0L,
        )
    }

    /** Only real audio entries: skips directories and videos. */
    fun tracks(children: List<ChildDto>): List<Track> =
        children.asSequence().filter { !it.isDir && !it.isVideo && it.mediaType != "video" }.map(::track).toList()

    fun playlist(dto: PlaylistDto): Playlist = Playlist(
        id = dto.id,
        serverId = serverId,
        name = dto.name,
        description = dto.comment?.takeIf { it.isNotBlank() },
        owner = dto.owner,
        trackCount = dto.songCount,
        durationMs = (dto.duration * 1000).toLong(),
        artworkId = dto.coverArt,
        artworkUrl = artworkUrl(dto.coverArt, DEFAULT_ART_SIZE),
        isPublic = dto.public,
        createdAt = parseIsoMillis(dto.created) ?: 0L,
        updatedAt = parseIsoMillis(dto.changed) ?: parseIsoMillis(dto.created) ?: 0L,
    )

    fun genre(dto: GenreDto): Genre = Genre(
        name = dto.value,
        serverId = serverId,
        albumCount = dto.albumCount,
        trackCount = dto.songCount,
    )

    fun lyrics(dto: StructuredLyricsDto): Lyrics? {
        if (dto.line.isEmpty()) return null
        val synced = dto.synced && dto.line.any { it.start != null }
        val lines = dto.line.map { line ->
            // LRC convention: a positive offset shifts lyrics earlier.
            val start = if (synced) line.start?.let { (it - dto.offset).coerceAtLeast(0L) } else null
            LyricLine(start, line.value)
        }
        return Lyrics(lines = lines, synced = synced, source = dto.lang)
    }

    fun lyrics(dto: LyricsDto): Lyrics? {
        val text = dto.value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Lyrics(lines = text.lines().map { LyricLine(null, it) }, synced = false)
    }

    companion object {
        const val DEFAULT_ART_SIZE = 600

        fun albumListType(type: AlbumListType): String = when (type) {
            AlbumListType.RECENTLY_ADDED -> "newest"
            AlbumListType.RECENTLY_PLAYED -> "recent"
            AlbumListType.MOST_PLAYED -> "frequent"
            AlbumListType.RANDOM -> "random"
            AlbumListType.STARRED -> "starred"
            AlbumListType.HIGHEST_RATED -> "highest"
            AlbumListType.ALPHABETICAL_BY_NAME -> "alphabeticalByName"
            AlbumListType.ALPHABETICAL_BY_ARTIST -> "alphabeticalByArtist"
            AlbumListType.BY_YEAR -> "byYear"
            AlbumListType.BY_GENRE -> "byGenre"
        }

        /** Subsonic `format` parameter for a codec; null means "no format param". */
        fun formatParam(codec: AudioCodec): String? = when (codec) {
            AudioCodec.ORIGINAL -> "raw"
            AudioCodec.FLAC -> "flac"
            AudioCodec.OPUS -> "opus"
            AudioCodec.AAC -> "aac"
            AudioCodec.MP3 -> "mp3"
            AudioCodec.VORBIS -> "ogg"
        }

        fun mimeType(codec: AudioCodec, original: String?): String? = when (codec) {
            AudioCodec.ORIGINAL -> original
            AudioCodec.FLAC -> "audio/flac"
            AudioCodec.OPUS -> "audio/ogg"
            AudioCodec.AAC -> "audio/aac"
            AudioCodec.MP3 -> "audio/mpeg"
            AudioCodec.VORBIS -> "audio/ogg"
        }
    }
}
