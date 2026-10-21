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
