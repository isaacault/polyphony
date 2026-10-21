package app.polyphony.core.requests

import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.CatalogAlbum
import app.polyphony.core.domain.model.CatalogResults
import app.polyphony.core.domain.model.CatalogTrack
import kotlinx.serialization.Serializable

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
)

@Serializable
internal data class CreateRequestBody(val kind: String, val musicBrainzReleaseGroupId: String)

@Serializable
internal data class CreatedRequestDto(val id: Long)

internal fun AvailabilityDto.toDomain(fallbackAlbumId: String?): Availability = when (state) {
    "in_library", "album_in_library" -> Availability.InLibrary(navidromeAlbumId ?: fallbackAlbumId)
    "partial" -> Availability.Partial(have ?: 0, expected ?: 0, navidromeAlbumId ?: fallbackAlbumId)
    "requestable" -> Availability.Requestable
    "catalog" -> Availability.CatalogOnly
    // requested | searching | downloading | importing | scanning | needs_attention, and any state
    // a newer server adds: something is under way, so never offer to request it again.
    else -> Availability.Requested(requestId, state, stillLooking)
}

/**
 * The hits a phone can act on. Artists are left out (there's no catalogue artist screen yet), and
 * [artworkHost]-less artwork is dropped: third-party image hosts would break "your traffic goes
 * only to your own server" (continuo#13 will serve them itself).
 */
internal fun SearchDto.toDomain(artworkHost: String?): CatalogResults {
    fun ownArtwork(url: String?): String? = url?.takeIf { artworkHost != null && hostOf(it) == artworkHost }
    val albums = results.filter { it.kind == "album" }.map { h ->
        CatalogAlbum(
            key = h.key,
            releaseGroupId = h.ids?.musicBrainzReleaseGroupId,
            title = h.title ?: h.name.orEmpty(),
            artist = h.artist,
            year = h.year,
            albumType = h.albumType,
            artworkUrl = ownArtwork(h.artwork?.url),
            availability = h.availability.toDomain(h.ids?.navidromeAlbumId),
        )
    }
    val tracks = results.filter { it.kind == "track" }.map { h ->
        CatalogTrack(
            key = h.key,
            title = h.title.orEmpty(),
            artist = h.artist,
            durationMs = h.durationMs,
            albumTitle = h.album?.title,
            albumReleaseGroupId = h.album?.musicBrainzReleaseGroupId,
            availability = h.availability.toDomain(h.album?.navidromeAlbumId),
        )
    }
    return CatalogResults(albums = albums, tracks = tracks, catalogAvailable = sources.catalog != "unavailable")
}

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
)

internal fun NotificationListDto.toDomain(since: Long) = app.polyphony.core.domain.model.InboxPage(
    items = items.map { it.toDomain() },
    cursor = cursor ?: items.lastOrNull()?.id ?: since,
    unreadCount = unreadCount,
)
