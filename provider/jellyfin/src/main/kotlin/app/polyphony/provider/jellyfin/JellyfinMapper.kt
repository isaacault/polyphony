package app.polyphony.provider.jellyfin

import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Artist
import app.polyphony.core.domain.model.Genre
import app.polyphony.core.domain.model.LyricLine
import app.polyphony.core.domain.model.Lyrics
import app.polyphony.core.domain.model.Playlist
import app.polyphony.core.domain.model.Track
import org.jellyfin.sdk.model.DateTime
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.LyricDto
import org.jellyfin.sdk.model.api.MediaStreamType
import java.time.ZoneId

internal const val TICKS_PER_MS = 10_000L

/** Converts Jellyfin DTOs into Polyphony domain models for one server. */
internal class JellyfinMapper(
    private val serverId: String,
    /** Builds an artwork URL for an item id at the given size. */
    private val imageUrl: (itemId: String, sizePx: Int) -> String?,
) {
    fun artist(dto: BaseItemDto): Artist {
        val artworkId = dto.ownPrimaryImageId()
        return Artist(
            id = dto.id.toString(),
            serverId = serverId,
            name = dto.name ?: "Unknown artist",
            sortName = dto.sortName,
            albumCount = dto.albumCount ?: dto.childCount ?: 0,
            artworkId = artworkId,
            artworkUrl = artworkId?.let { imageUrl(it, DEFAULT_ARTWORK_PX) },
            starred = dto.userData?.isFavorite == true,
            rating = likesToRating(dto.userData?.likes),
            musicBrainzId = dto.providerId("MusicBrainzArtist"),
            biography = dto.overview?.takeIf { it.isNotBlank() },
        )
    }

    fun album(dto: BaseItemDto): Album {
        val artworkId = dto.ownPrimaryImageId()
        val albumArtist = dto.albumArtist ?: dto.albumArtists?.firstOrNull()?.name ?: dto.artists?.firstOrNull()
        return Album(
            id = dto.id.toString(),
            serverId = serverId,
            name = dto.name ?: "Unknown album",
            sortName = dto.sortName,
            artistId = dto.albumArtists?.firstOrNull()?.id?.toString() ?: dto.artistItems?.firstOrNull()?.id?.toString(),
            artistName = albumArtist,
            year = dto.productionYear,
            genre = dto.genres?.firstOrNull(),
            trackCount = dto.childCount ?: dto.songCount ?: 0,
            durationMs = ((dto.runTimeTicks ?: dto.cumulativeRunTimeTicks) ?: 0L) / TICKS_PER_MS,
            artworkId = artworkId,
            artworkUrl = artworkId?.let { imageUrl(it, DEFAULT_ARTWORK_PX) },
            starred = dto.userData?.isFavorite == true,
            rating = likesToRating(dto.userData?.likes),
            playCount = dto.userData?.playCount?.toLong() ?: 0L,
            createdAt = dto.dateCreated.toEpochMs() ?: 0L,
            lastPlayedAt = dto.userData?.lastPlayedDate.toEpochMs(),
            isCompilation = albumArtist?.equals("Various Artists", ignoreCase = true) == true,
            musicBrainzId = dto.providerId("MusicBrainzAlbum") ?: dto.providerId("MusicBrainzReleaseGroup"),
        )
    }

    fun track(dto: BaseItemDto): Track {
        val source = dto.mediaSources?.firstOrNull()
        val audioStream = (source?.mediaStreams ?: dto.mediaStreams)?.firstOrNull { it.type == MediaStreamType.AUDIO }
        val container = (source?.container ?: dto.container)?.lowercase()?.substringBefore(',')?.takeIf { it.isNotBlank() }
        val artworkId = dto.trackArtworkId()
        val artists = dto.artists?.filter { it.isNotBlank() }.orEmpty()
        return Track(
            id = dto.id.toString(),
            serverId = serverId,
            title = dto.name ?: "Unknown title",
            albumId = dto.albumId?.toString(),
            albumName = dto.album,
            artistId = dto.artistItems?.firstOrNull()?.id?.toString() ?: dto.albumArtists?.firstOrNull()?.id?.toString(),
            artistName = artists.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: dto.albumArtist,
            albumArtistName = dto.albumArtist ?: dto.albumArtists?.firstOrNull()?.name,
            trackNumber = dto.indexNumber,
            discNumber = dto.parentIndexNumber,
            year = dto.productionYear,
            genre = dto.genres?.firstOrNull(),
            durationMs = ((dto.runTimeTicks ?: source?.runTimeTicks) ?: 0L) / TICKS_PER_MS,
            bitrateKbps = (source?.bitrate ?: audioStream?.bitRate)?.div(1000)?.takeIf { it > 0 },
            sampleRateHz = audioStream?.sampleRate,
            bitDepth = audioStream?.bitDepth,
            channels = audioStream?.channels,
            container = container,
            mimeType = JellyfinStreamParams.directMimeType(container),
            sizeBytes = source?.size,
            artworkId = artworkId,
            artworkUrl = artworkId?.let { imageUrl(it, DEFAULT_ARTWORK_PX) },
            starred = dto.userData?.isFavorite == true,
            rating = likesToRating(dto.userData?.likes),
            playCount = dto.userData?.playCount?.toLong() ?: 0L,
            replayGainTrackDb = dto.normalizationGain?.toDouble(),
            replayGainAlbumDb = dto.albumNormalizationGain?.toDouble(),
            musicBrainzId = dto.providerId("MusicBrainzTrack") ?: dto.providerId("MusicBrainzRecording"),
            path = dto.path,
            createdAt = dto.dateCreated.toEpochMs() ?: 0L,
        )
    }

    fun playlist(dto: BaseItemDto, isPublic: Boolean = false): Playlist {
        val artworkId = dto.ownPrimaryImageId()
        return Playlist(
            id = dto.id.toString(),
            serverId = serverId,
            name = dto.name ?: "Playlist",
            description = dto.overview?.takeIf { it.isNotBlank() },
            owner = null,
            trackCount = dto.childCount ?: dto.songCount ?: 0,
            durationMs = ((dto.cumulativeRunTimeTicks ?: dto.runTimeTicks) ?: 0L) / TICKS_PER_MS,
            artworkId = artworkId,
            artworkUrl = artworkId?.let { imageUrl(it, DEFAULT_ARTWORK_PX) },
            isPublic = isPublic,
            createdAt = dto.dateCreated.toEpochMs() ?: 0L,
            updatedAt = (dto.dateLastMediaAdded ?: dto.dateCreated).toEpochMs() ?: 0L,
        )
    }

    fun genre(dto: BaseItemDto): Genre = Genre(
        name = dto.name ?: "Unknown genre",
        serverId = serverId,
        albumCount = dto.albumCount ?: 0,
        trackCount = dto.songCount ?: 0,
    )

    fun lyrics(dto: LyricDto): Lyrics? {
        val lines = dto.lyrics.map { LyricLine(startMs = it.start?.let { s -> s / TICKS_PER_MS }, text = it.text) }
        if (lines.isEmpty()) return null
        val synced = dto.metadata.isSynced ?: lines.any { it.startMs != null }
        return Lyrics(lines = lines, synced = synced, source = "Jellyfin")
    }

    companion object {
        const val DEFAULT_ARTWORK_PX = 600

        fun likesToRating(likes: Boolean?): Int = when (likes) {
            true -> 5
            false -> 1
            null -> 0
        }
    }
}

/** The item's own primary image, if any. */
internal fun BaseItemDto.ownPrimaryImageId(): String? =
    if (imageTags?.containsKey(ImageType.PRIMARY) == true) id.toString() else null

/** Tracks usually have no image of their own; fall back to the album's. */
internal fun BaseItemDto.trackArtworkId(): String? =
    ownPrimaryImageId()
        ?: albumId?.takeIf { albumPrimaryImageTag != null }?.toString()
        ?: parentPrimaryImageItemId?.toString()
        ?: albumId?.toString()

internal fun BaseItemDto.providerId(key: String): String? =
    providerIds?.entries?.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }

/** The SDK parses timestamps into the system zone; `0001-01-01` means "unset". */
internal fun DateTime?.toEpochMs(): Long? =
    this?.takeIf { it.year > 1 }?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
