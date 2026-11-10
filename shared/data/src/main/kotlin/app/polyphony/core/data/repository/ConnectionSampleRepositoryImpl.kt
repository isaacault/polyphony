package app.polyphony.core.data.repository

import app.polyphony.core.data.db.dao.ConnectionSampleDao
import app.polyphony.core.data.db.entity.ConnectionSampleEntity
import app.polyphony.core.domain.connection.ConnectionRetention
import app.polyphony.core.domain.connection.ConnectionSample
import app.polyphony.core.domain.repository.ConnectionSampleRepository
import app.polyphony.core.domain.transcode.AudioCodec
import app.polyphony.core.domain.transcode.NetworkKind
import app.polyphony.core.domain.transcode.TranscodeDecision
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionSampleRepositoryImpl @Inject constructor(
    private val dao: ConnectionSampleDao,
) : ConnectionSampleRepository {

    private val retention = ConnectionRetention()

    override suspend fun record(sample: ConnectionSample) {
        dao.insert(sample.toEntity())
        dao.deleteEndedBefore(retention.cutoffMs(System.currentTimeMillis()))
        dao.keepNewest(retention.maxRows)
    }

    override suspend fun all(): List<ConnectionSample> = dao.all().map { it.toDomain() }

    override suspend fun clear() = dao.deleteAll()
}

internal fun ConnectionSample.toEntity() = ConnectionSampleEntity(
    startedAtMs = startedAtMs,
    endedAtMs = endedAtMs,
    endedBy = trigger.name,
    network = network.name,
    metered = metered,
    vpn = vpn,
    atHome = atHome,
    responseMs = responseMs,
    networkBytes = networkBytes,
    networkMs = networkMs,
    throughputKbps = throughputKbps,
    cachedBytes = cachedBytes,
    downloadedBytes = downloadedBytes,
    stalls = stalls,
    stallMs = stallMs,
    serverId = serverId,
    profileId = profileId,
    codec = codec?.name,
    maxBitrateKbps = maxBitrateKbps,
    reason = reason?.name,
    capped = capped,
    cappedMs = cappedMs,
)

internal fun ConnectionSampleEntity.toDomain() = ConnectionSample(
    startedAtMs = startedAtMs,
    endedAtMs = endedAtMs,
    trigger = enumOrNull<ConnectionSample.Trigger>(endedBy) ?: ConnectionSample.Trigger.PERIODIC,
    network = enumOrNull<NetworkKind>(network) ?: NetworkKind.OTHER,
    metered = metered,
    vpn = vpn,
    atHome = atHome,
    responseMs = responseMs,
    networkBytes = networkBytes,
    networkMs = networkMs,
    throughputKbps = throughputKbps,
    cachedBytes = cachedBytes,
    downloadedBytes = downloadedBytes,
    stalls = stalls,
    stallMs = stallMs,
    serverId = serverId,
    profileId = profileId,
    codec = codec?.let { enumOrNull<AudioCodec>(it) },
    maxBitrateKbps = maxBitrateKbps,
    reason = reason?.let { enumOrNull<TranscodeDecision.Reason>(it) },
    capped = capped,
    cappedMs = cappedMs,
)

private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? = enumValues<E>().firstOrNull { it.name == name }
