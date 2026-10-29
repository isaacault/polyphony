package app.polyphony.core.downloads.db

import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.repository.DownloadRule
import app.polyphony.core.domain.repository.DownloadRuleTarget
import app.polyphony.core.domain.repository.DownloadedTrack
import kotlinx.serialization.json.Json

internal fun DownloadEntity.toDomain(): DownloadedTrack = DownloadedTrack(
    trackId = trackId,
    serverId = serverId,
    status = status,
    progress = progress,
    filePath = filePath,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    profileId = profileId,
    pinned = pinned,
    ruleId = ruleId,
    error = error,
    createdAt = createdAt,
    lastAccessAt = lastAccessAt,
)

internal fun DownloadEntity.track(json: Json): Track? =
    runCatching { json.decodeFromString(Track.serializer(), trackJson) }.getOrNull()

internal fun Track.toJson(json: Json): String = json.encodeToString(Track.serializer(), this)

internal fun DownloadRuleEntity.toDomain(json: Json): DownloadRule? {
    val target = runCatching { json.decodeFromString(DownloadRuleTarget.serializer(), targetJson) }.getOrNull()
        ?: return null
    return DownloadRule(
        id = id,
        serverId = serverId,
        target = target,
        enabled = enabled,
        profileId = profileId,
        maxTracks = maxTracks,
        createdAt = createdAt,
        lastEvaluatedAt = lastEvaluatedAt,
    )
}

internal fun DownloadRule.toEntity(json: Json): DownloadRuleEntity = DownloadRuleEntity(
    id = id,
    serverId = serverId,
    targetJson = json.encodeToString(DownloadRuleTarget.serializer(), target),
    enabled = enabled,
    profileId = profileId,
    maxTracks = maxTracks,
    createdAt = createdAt,
    lastEvaluatedAt = lastEvaluatedAt,
)
