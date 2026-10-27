package app.polyphony.core.requests

import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.CatalogAlbum
import app.polyphony.core.domain.model.CatalogArtist
import app.polyphony.core.domain.model.CatalogArtistPage
import app.polyphony.core.domain.model.CatalogResults
import app.polyphony.core.domain.model.CatalogTrack
import app.polyphony.core.domain.model.PlayNowStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

// Wire shapes of continuo's /v1 API (continuo PLAN.md §3). Unknown keys are ignored (the app's
// Json has ignoreUnknownKeys), and every field the app can live without is optional, so a newer
// server never breaks an older app.

@Serializable
internal data class RefreshBody(val refreshToken: String)

@Serializable
internal data class SessionDto(val accessToken: String, val expiresIn: Long = 3600, val refreshToken: String)

@Serializable
internal data class ErrorEnvelope(val error: ErrorDto? = null)

@Serializable
internal data class ErrorDto(
    val code: String? = null,
    val message: String? = null,
    val requestId: Long? = null,
    val navidromeAlbumId: String? = null,
)

@Serializable
internal data class SearchDto(
    val sources: SourcesDto = SourcesDto(),
    val results: List<HitDto> = emptyList(),
)

@Serializable
internal data class SourcesDto(val library: String = "ok", val catalog: String = "ok")

@Serializable
internal data class ArtworkDto(val url: String? = null)

@Serializable
internal data class IdsDto(
    val musicBrainzReleaseGroupId: String? = null,
    val navidromeAlbumId: String? = null,
    val musicBrainzArtistId: String? = null,
)

@Serializable
internal data class AlbumRefDto(
    val title: String? = null,
    val musicBrainzReleaseGroupId: String? = null,
    val navidromeAlbumId: String? = null,
)

@Serializable
internal data class AvailabilityDto(
    val state: String = "requestable",
    val navidromeAlbumId: String? = null,
    val requestId: Long? = null,
    val stillLooking: Boolean = false,
    val have: Int? = null,
    val expected: Int? = null,
    val storeAlbumId: String? = null,
    val requestable: Boolean = false,
)

@Serializable
internal data class HitDto(
    val kind: String,
    val key: String,
    val title: String? = null,
    val name: String? = null,
    val artist: String? = null,
    val year: Int? = null,
    val albumType: String? = null,
    val durationMs: Long? = null,
    val artwork: ArtworkDto? = null,
    val ids: IdsDto? = null,
    val album: AlbumRefDto? = null,
    val availability: AvailabilityDto = AvailabilityDto(),
    /** Only on `GET /v1/catalog/albums/{mbid}`. */
    val tracks: List<TracklistDto> = emptyList(),
)

@Serializable
internal data class TracklistDto(
    val position: Int = 0,
    val title: String = "",
    val durationMs: Long? = null,
    val discNumber: Int = 1,
    val musicBrainzRecordingId: String? = null,
)

@Serializable
internal data class CreateRequestBody(val kind: String, val musicBrainzReleaseGroupId: String)

@Serializable
internal data class PlayNowBody(
    val musicBrainzRecordingId: String,
    val musicBrainzReleaseGroupId: String? = null,
    val title: String,
    val artist: String,
    val durationMs: Long? = null,
    val queued: Boolean = false,
)

@Serializable
internal data class PlayNowPriorityBody(val ids: List<String>)

@Serializable
internal data class PlayNowDto(
    val id: String,
    val status: String = "searching",
    val playable: Boolean = false,
    val percent: Double = 0.0,
    val title: String? = null,
    val artist: String? = null,
    val durationMs: Long? = null,
    val format: String? = null,
    val error: String? = null,
) {
    fun toDomain() = PlayNowStream(id, status, playable, percent, title.orEmpty(), artist, durationMs, format, error)
}

@Serializable
internal data class CreateTrackRequestBody(
    val kind: String,
    val musicBrainzRecordingId: String,
    val musicBrainzReleaseGroupId: String,
)

@Serializable
internal data class CreatedRequestDto(val id: Long)

internal fun AvailabilityDto.toDomain(fallbackAlbumId: String?): Availability = when (state) {
    "in_library", "album_in_library" -> Availability.InLibrary(navidromeAlbumId ?: fallbackAlbumId)
    "partial" -> Availability.Partial(have ?: 0, expected ?: 0, navidromeAlbumId ?: fallbackAlbumId, requestable)
    "requestable" -> Availability.Requestable
    "on_server" -> storeAlbumId?.let { Availability.OnServer(it) } ?: Availability.Requestable
    "catalog" -> Availability.CatalogOnly
    // requested | searching | downloading | importing | scanning | needs_attention, and any state
    // a newer server adds: something is under way, so never offer to request it again.
    else -> Availability.Requested(requestId, state, stillLooking)
}

@Serializable
internal data class BiographyDto(val text: String = "", val source: String? = null, val url: String? = null)

/** `GET /v1/catalog/artists/{mbid}`: an artist's page. */
@Serializable
internal data class CatalogArtistDto(
    val key: String,
    val name: String = "",
    val disambiguation: String? = null,
    val artwork: ArtworkDto? = null,
    val biography: BiographyDto? = null,
    val ids: IdsDto? = null,
    val availability: AvailabilityDto = AvailabilityDto(state = "catalog"),
    val topTracks: List<HitDto> = emptyList(),
    val similarArtists: List<HitDto> = emptyList(),
)

internal fun CatalogArtistDto.toDomain(artworkHost: String?) = CatalogArtistPage(
    artist = CatalogArtist(
        key = key,
        musicBrainzId = ids?.musicBrainzArtistId,
        name = name,
        artworkUrl = ownArtworkUrl(artwork?.url, artworkHost),
        availability = availability.toDomain(null),
    ),
    disambiguation = disambiguation?.takeIf { it.isNotBlank() },
    biography = biography?.text?.takeIf { it.isNotBlank() },
    biographyUrl = biography?.url,
    topTracks = topTracks.filter { it.kind == "track" }.map { it.toCatalogTrack() },
    similarArtists = similarArtists.filter { it.kind == "artist" }.map { it.toCatalogArtist(artworkHost) },
)

/**
 * The hits a phone can act on. Artwork not on [artworkHost] is dropped: third-party image hosts
 * would break "your traffic goes only to your own server" (continuo proxies catalogue art itself).
 */
internal fun SearchDto.toDomain(artworkHost: String?): CatalogResults {
    val artists = results.filter { it.kind == "artist" }.map { it.toCatalogArtist(artworkHost) }
    val albums = results.filter { it.kind == "album" }.map { it.toCatalogAlbum(artworkHost) }
    val tracks = results.filter { it.kind == "track" }.map { it.toCatalogTrack() }
    return CatalogResults(artists = artists, albums = albums, tracks = tracks, catalogAvailable = sources.catalog != "unavailable")
}

internal fun HitDto.toCatalogArtist(artworkHost: String?) = CatalogArtist(
    key = key,
    musicBrainzId = ids?.musicBrainzArtistId,
    name = name ?: title.orEmpty(),
    artworkUrl = ownArtworkUrl(artwork?.url, artworkHost),
    availability = availability.toDomain(null),
)

internal fun HitDto.toCatalogTrack() = CatalogTrack(
    key = key,
    title = title.orEmpty(),
    artist = artist,
    durationMs = durationMs,
    albumTitle = album?.title,
    albumReleaseGroupId = album?.musicBrainzReleaseGroupId,
    availability = availability.toDomain(album?.navidromeAlbumId),
    recordingId = key.removePrefix("mb:rec:").takeIf { key.startsWith("mb:rec:") && it.isNotBlank() },
)

internal fun HitDto.toCatalogAlbum(artworkHost: String?) = CatalogAlbum(
    key = key,
    releaseGroupId = ids?.musicBrainzReleaseGroupId,
    title = title ?: name.orEmpty(),
    artist = artist,
    year = year,
    albumType = albumType,
    artworkUrl = ownArtworkUrl(artwork?.url, artworkHost),
    availability = availability.toDomain(ids?.navidromeAlbumId),
)

private fun ownArtworkUrl(url: String?, artworkHost: String?): String? =
    url?.takeIf { artworkHost != null && hostOf(it) == artworkHost }

internal fun hostOf(url: String): String? = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()

// ---- requests (continuo PLAN.md §3.3) ----

@Serializable
internal data class RequestListDto(val items: List<RequestDto> = emptyList())

@Serializable
internal data class RequestIdsDto(
    val musicBrainzReleaseGroupId: String? = null,
    val navidromeAlbumId: String? = null,
)

@Serializable
internal data class DownloadDto(val stalled: Boolean = false, val queueId: Long? = null)

@Serializable
internal data class AttentionDto(val reason: String? = null)

@Serializable
internal data class PipelineDto(
    val stages: List<String> = emptyList(),
    val stage: String? = null,
    val stageIndex: Int = 0,
    val percent: Float = 0f,
    val detail: String = "",
    val source: String? = null,
    val download: DownloadDto? = null,
    val attention: AttentionDto? = null,
)

@Serializable
internal data class RequestErrorDto(val message: String? = null)

@Serializable
internal data class PlayDto(val navidromeAlbumId: String? = null)

@Serializable
internal data class RequestDto(
    val id: Long,
    val title: String? = null,
    val artist: String? = null,
    val year: Int? = null,
    val artwork: ArtworkDto? = null,
    val ids: RequestIdsDto? = null,
    val status: String = "pending",
    val stillLooking: Boolean = false,
    val pipeline: PipelineDto? = null,
    val error: RequestErrorDto? = null,
    val requestedBy: String? = null,
    val createdAt: String? = null,
    val play: PlayDto? = null,
)

/** `request.updated` SSE payload: a slim request. */
@Serializable
internal data class RequestUpdateDto(
    val id: Long,
    val status: String,
    val stillLooking: Boolean = false,
    val pipeline: PipelineDto? = null,
    val play: PlayDto? = null,
)

@Serializable
internal data class ActionResultDto(val ok: Boolean = true, val message: String? = null)

internal fun PipelineDto.toDomain() = app.polyphony.core.domain.model.RequestPipeline(
    stages = stages,
    stageIndex = stageIndex,
    percent = percent,
    detail = detail,
    source = source,
    hasDownload = download?.queueId != null,
    stalled = download?.stalled == true,
    attentionReason = attention?.reason,
)

internal fun RequestDto.toDomain(artworkHost: String?) = app.polyphony.core.domain.model.MusicRequest(
    id = id,
    title = title.orEmpty(),
    artist = artist,
    year = year,
    artworkUrl = artwork?.url?.takeIf { artworkHost != null && hostOf(it) == artworkHost },
    status = status,
    stillLooking = stillLooking,
    pipeline = pipeline?.toDomain(),
    error = error?.message,
    releaseGroupId = ids?.musicBrainzReleaseGroupId,
    libraryAlbumId = play?.navidromeAlbumId ?: ids?.navidromeAlbumId?.takeIf { status == "available" },
    requestedBy = requestedBy,
    createdAt = createdAt,
)

internal fun RequestUpdateDto.toDomain() = app.polyphony.core.domain.model.RequestEvent.Updated(
    id = id,
    status = status,
    stillLooking = stillLooking,
    pipeline = pipeline?.toDomain(),
    libraryAlbumId = play?.navidromeAlbumId,
)

// ---- devices (continuo PLAN.md §3.1) ----

@Serializable
internal data class DeviceDto(
    val id: String,
    val name: String? = null,
    val platform: String? = null,
    val createdAt: String? = null,
    val lastSeenAt: String? = null,
    val current: Boolean = false,
)

@Serializable
internal data class RevokedDto(val ok: Boolean = true, val revoked: Int = 0)

internal fun DeviceDto.toDomain() = app.polyphony.core.domain.model.ContinuoDevice(
    id = id,
    name = name?.takeIf { it.isNotBlank() } ?: id,
    platform = platform?.takeIf { it.isNotBlank() },
    createdAtMillis = parseTimestamp(createdAt),
    lastSeenAtMillis = parseTimestamp(lastSeenAt),
    current = current,
)

// ---- notifications (continuo PLAN.md §3.4) ----

@Serializable
internal data class NotificationActionDto(
    val type: String? = null,
    val deepLink: String? = null,
    val navidromeAlbumId: String? = null,
    val requestId: Long? = null,
)

@Serializable
internal data class NotificationDto(
    val id: Long,
    val type: String = "",
    val title: String? = null,
    val body: String? = null,
    val createdAt: String? = null,
    val readAt: String? = null,
    val action: NotificationActionDto? = null,
    val requestId: Long? = null,
    /** A set of "homeassistant" / "sse" (continuo#16); kept loose so an odd shape can't fail the page. */
    val deliveredVia: JsonElement? = null,
)

@Serializable
internal data class NotificationListDto(
    val items: List<NotificationDto> = emptyList(),
    val cursor: Long? = null,
    val unreadCount: Int = 0,
)

@Serializable
internal data class MarkReadIdsBody(val ids: List<Long>)

@Serializable
internal data class MarkReadDto(val ok: Boolean = true, val marked: Int = 0, val unreadCount: Int = 0)

/** continuo writes `2026-10-21T08:30:00+00:00`; anything unparseable is simply left out. */
internal fun parseTimestamp(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return runCatching { java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }
        .recoverCatching { java.time.LocalDateTime.parse(value).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }
        .getOrNull()
}

internal fun NotificationDto.toDomain() = app.polyphony.core.domain.model.InboxNotification(
    id = id,
    type = type,
    title = title.orEmpty(),
    body = body?.takeIf { it.isNotBlank() },
    createdAtMillis = parseTimestamp(createdAt),
    read = readAt != null,
    deepLink = action?.deepLink?.takeIf { it.isNotBlank() },
    libraryAlbumId = action?.navidromeAlbumId,
    requestId = action?.requestId ?: requestId,
    deliveredVia = deliveryChannels(deliveredVia),
)

/** `deliveredVia` as a lower-cased set: an array of strings (or, leniently, one string); anything else is none. */
internal fun deliveryChannels(value: JsonElement?): Set<String> {
    fun JsonElement.name(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    return when (value) {
        is JsonArray -> value.mapNotNull { it.name() }.toSet()
        is JsonPrimitive -> setOfNotNull(value.name())
        else -> emptySet()
    }
}

// The cursor is the highest id returned in either order (continuo sends it; older servers didn't
// always), or the `since` asked for when nothing was newer.
internal fun NotificationListDto.toDomain(since: Long) = app.polyphony.core.domain.model.InboxPage(
    items = items.map { it.toDomain() },
    cursor = cursor ?: items.maxOfOrNull { it.id } ?: since,
    unreadCount = unreadCount,
)

// ---- account and library removal (continuo#23) ----

@Serializable
internal data class MeUserDto(val username: String = "", val role: String = "user")

@Serializable
internal data class MeDto(val user: MeUserDto = MeUserDto(), val capabilities: List<String> = emptyList())

@Serializable
internal data class RemovedIdsDto(val navidromeAlbumId: String? = null)

@Serializable
internal data class RemovedDto(
    val id: Long,
    val title: String? = null,
    val artist: String? = null,
    val removedAt: String? = null,
    val removedBy: String? = null,
    val restorableUntil: String? = null,
    val ids: RemovedIdsDto? = null,
)

internal fun RemovedDto.toDomain() = app.polyphony.core.domain.model.RemovedAlbum(
    id = id,
    title = title.orEmpty(),
    artist = artist,
    removedAtMillis = parseTimestamp(removedAt),
    removedBy = removedBy,
    restorableUntilMillis = parseTimestamp(restorableUntil),
    libraryAlbumId = ids?.navidromeAlbumId,
)

/** `GET /v1/catalog/artists/resolve`: the catalogue artist a library artist without an id is. */
@Serializable
internal data class ResolvedArtistDto(val musicBrainzArtistId: String, val name: String = "")

/** `GET /v1/library/artist-names`. */
@Serializable
internal data class ArtistNameDto(val name: String, val originalName: String? = null, val sortName: String? = null)
