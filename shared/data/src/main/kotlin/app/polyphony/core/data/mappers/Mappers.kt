package app.polyphony.core.data.mappers

import app.polyphony.core.data.db.entity.AlbumEntity
import app.polyphony.core.data.db.entity.ArtistEntity
import app.polyphony.core.data.db.entity.GenreEntity
import app.polyphony.core.data.db.entity.PlaylistEntity
import app.polyphony.core.data.db.entity.RemoteDeviceEntity
import app.polyphony.core.data.db.entity.ServerEntity
import app.polyphony.core.data.db.entity.TrackEntity
import app.polyphony.core.data.db.entity.TranscodeProfileEntity
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Artist
import app.polyphony.core.domain.model.Genre
import app.polyphony.core.domain.model.Playlist
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerType
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.repository.RemoteDevicePreference
import app.polyphony.core.domain.transcode.AudioCodec
import app.polyphony.core.domain.transcode.TranscodeProfile
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val optionsCodec = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun decodeOptions(raw: String): Map<String, String> =
    if (raw.isBlank()) emptyMap()
    else runCatching { optionsCodec.decodeFromString(MapSerializer(String.serializer(), String.serializer()), raw) }.getOrDefault(emptyMap())

internal fun encodeOptions(options: Map<String, String>): String =
    optionsCodec.encodeToString(MapSerializer(String.serializer(), String.serializer()), options)

// ---- Server ----

fun ServerEntity.toDomain(): Server = Server(
    id = id,
    type = runCatching { ServerType.valueOf(type) }.getOrDefault(ServerType.SUBSONIC),
    name = name,
    baseUrl = baseUrl,
    username = username,
    credential = credential,
    userId = userId,
    deviceId = deviceId,
    options = decodeOptions(optionsJson),
    createdAt = createdAt,
)

fun Server.toEntity(): ServerEntity = ServerEntity(
    id = id,
    type = type.name,
    name = name,
    baseUrl = baseUrl,
    username = username,
    credential = credential,
    userId = userId,
    deviceId = deviceId,
    optionsJson = encodeOptions(options),
    createdAt = createdAt,
)

// ---- Artist ----

fun ArtistEntity.toDomain(): Artist = Artist(
    id = id, serverId = serverId, name = name, sortName = sortName, albumCount = albumCount,
    artworkId = artworkId, artworkUrl = artworkUrl, starred = starred, rating = rating,
    musicBrainzId = musicBrainzId, biography = biography,
)

fun Artist.toEntity(generation: Long): ArtistEntity = ArtistEntity(
    id = id, serverId = serverId, name = name, sortName = sortName, albumCount = albumCount,
    artworkId = artworkId, artworkUrl = artworkUrl, starred = starred, rating = rating,
    musicBrainzId = musicBrainzId, biography = biography, syncGeneration = generation,
)

// ---- Album ----

fun AlbumEntity.toDomain(): Album = Album(
    id = id, serverId = serverId, name = name, sortName = sortName, artistId = artistId, artistName = artistName,
    year = year, genre = genre, trackCount = trackCount, durationMs = durationMs, artworkId = artworkId,
    artworkUrl = artworkUrl, starred = starred, rating = rating, playCount = playCount, createdAt = createdAt,
    lastPlayedAt = lastPlayedAt, isCompilation = isCompilation, musicBrainzId = musicBrainzId,
)

fun Album.toEntity(generation: Long): AlbumEntity = AlbumEntity(
    id = id, serverId = serverId, name = name, sortName = sortName, artistId = artistId, artistName = artistName,
    year = year, genre = genre, trackCount = trackCount, durationMs = durationMs, artworkId = artworkId,
    artworkUrl = artworkUrl, starred = starred, rating = rating, playCount = playCount, createdAt = createdAt,
    lastPlayedAt = lastPlayedAt, isCompilation = isCompilation, musicBrainzId = musicBrainzId,
    syncGeneration = generation,
)

// ---- Track ----

fun TrackEntity.toDomain(): Track = Track(
    id = id, serverId = serverId, title = title, albumId = albumId, albumName = albumName, artistId = artistId,
    artistName = artistName, albumArtistName = albumArtistName, trackNumber = trackNumber, discNumber = discNumber,
    year = year, genre = genre, durationMs = durationMs, bitrateKbps = bitrateKbps, sampleRateHz = sampleRateHz,
    bitDepth = bitDepth, channels = channels, container = container, mimeType = mimeType, sizeBytes = sizeBytes,
    artworkId = artworkId, artworkUrl = artworkUrl, starred = starred, rating = rating, playCount = playCount,
    replayGainTrackDb = replayGainTrackDb, replayGainAlbumDb = replayGainAlbumDb,
    replayGainTrackPeak = replayGainTrackPeak, replayGainAlbumPeak = replayGainAlbumPeak,
    musicBrainzId = musicBrainzId, path = path, createdAt = createdAt,
)

fun Track.toEntity(generation: Long, lastPlayedAt: Long? = null): TrackEntity = TrackEntity(
    id = id, serverId = serverId, title = title, albumId = albumId, albumName = albumName, artistId = artistId,
    artistName = artistName, albumArtistName = albumArtistName, trackNumber = trackNumber, discNumber = discNumber,
    year = year, genre = genre, durationMs = durationMs, bitrateKbps = bitrateKbps, sampleRateHz = sampleRateHz,
    bitDepth = bitDepth, channels = channels, container = container, mimeType = mimeType, sizeBytes = sizeBytes,
    artworkId = artworkId, artworkUrl = artworkUrl, starred = starred, rating = rating, playCount = playCount,
    lastPlayedAt = lastPlayedAt, replayGainTrackDb = replayGainTrackDb, replayGainAlbumDb = replayGainAlbumDb,
    replayGainTrackPeak = replayGainTrackPeak, replayGainAlbumPeak = replayGainAlbumPeak,
    musicBrainzId = musicBrainzId, path = path, createdAt = createdAt, syncGeneration = generation,
)

// ---- Playlist ----

fun PlaylistEntity.toDomain(): Playlist = Playlist(
    id = id, serverId = serverId, name = name, description = description, owner = owner, trackCount = trackCount,
    durationMs = durationMs, artworkId = artworkId, artworkUrl = artworkUrl, isPublic = isPublic,
    createdAt = createdAt, updatedAt = updatedAt,
)

fun Playlist.toEntity(generation: Long): PlaylistEntity = PlaylistEntity(
    id = id, serverId = serverId, name = name, description = description, owner = owner, trackCount = trackCount,
    durationMs = durationMs, artworkId = artworkId, artworkUrl = artworkUrl, isPublic = isPublic,
    createdAt = createdAt, updatedAt = updatedAt, syncGeneration = generation,
)

// ---- Genre ----

fun GenreEntity.toDomain(): Genre = Genre(name = name, serverId = serverId, albumCount = albumCount, trackCount = trackCount)

fun Genre.toEntity(generation: Long): GenreEntity =
    GenreEntity(name = name, serverId = serverId, albumCount = albumCount, trackCount = trackCount, syncGeneration = generation)

// ---- Transcode profiles / remote devices ----

fun TranscodeProfileEntity.toDomain(): TranscodeProfile = TranscodeProfile(
    id = id, name = name,
    codec = runCatching { AudioCodec.valueOf(codec) }.getOrDefault(AudioCodec.ORIGINAL),
    maxBitrateKbps = maxBitrateKbps, maxSampleRateHz = maxSampleRateHz, maxBitDepth = maxBitDepth, builtIn = false,
)

fun TranscodeProfile.toEntity(): TranscodeProfileEntity = TranscodeProfileEntity(
    id = id, name = name, codec = codec.name, maxBitrateKbps = maxBitrateKbps,
    maxSampleRateHz = maxSampleRateHz, maxBitDepth = maxBitDepth,
)

fun RemoteDeviceEntity.toDomain(): RemoteDevicePreference = RemoteDevicePreference(
    targetKey = targetKey, name = name, modelName = modelName, profileId = profileId,
    lastSeenAt = lastSeenAt, lastConnectedAt = lastConnectedAt,
)

fun RemoteDevicePreference.toEntity(): RemoteDeviceEntity = RemoteDeviceEntity(
    targetKey = targetKey, name = name, modelName = modelName, profileId = profileId,
    lastSeenAt = lastSeenAt, lastConnectedAt = lastConnectedAt,
)
