package app.polyphony.core.data.mappers

import app.polyphony.core.data.db.entity.PlayContextEntity
import app.polyphony.core.domain.model.PlayContext
import app.polyphony.core.domain.model.RecentContext

internal fun RecentContext.toEntity() = PlayContextEntity(
    key = context.key,
    serverId = context.serverId,
    kind = context.kind.name,
    contextId = context.id,
    title = context.title,
    subtitle = context.subtitle,
    artworkUrl = context.artworkUrl,
    trackId = trackId,
    trackTitle = trackTitle,
    trackIndex = trackIndex,
    positionMs = positionMs,
    shuffle = shuffle,
    updatedAt = updatedAt,
)

/** `null` when the stored kind is unknown to this build (older/newer app versions). */
internal fun PlayContextEntity.toDomain(): RecentContext? {
    val kind = PlayContext.Kind.entries.firstOrNull { it.name == this.kind } ?: return null
    return RecentContext(
        context = PlayContext(kind = kind, id = contextId, serverId = serverId, title = title, subtitle = subtitle, artworkUrl = artworkUrl),
        trackId = trackId,
        trackTitle = trackTitle,
        trackIndex = trackIndex,
        positionMs = positionMs,
        shuffle = shuffle,
        updatedAt = updatedAt,
    )
}
