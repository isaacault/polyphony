package app.polyphony.core.scrobble.queue

import app.polyphony.core.domain.model.ScrobbleAccount
import app.polyphony.core.domain.model.ScrobbleService
import app.polyphony.core.scrobble.Scrobble
import app.polyphony.core.scrobble.db.QueuedScrobbleEntity
import app.polyphony.core.scrobble.db.ScrobbleAccountEntity
import app.polyphony.core.scrobble.db.ScrobbleDao
import kotlinx.serialization.json.Json
import javax.inject.Inject

class RoomScrobbleQueue @Inject constructor(
    private val dao: ScrobbleDao,
    private val json: Json,
) : ScrobbleQueue {

    override suspend fun accounts(): List<ScrobbleAccount> = dao.accounts().mapNotNull { it.toDomain() }

    override suspend fun markNeedsReconnect(service: ScrobbleService) = dao.markNeedsReconnect(service.name)

    override suspend fun oldest(service: ScrobbleService, limit: Int): List<QueuedScrobble> {
        val rows = dao.oldest(service.name, limit)
        val unreadable = mutableListOf<Long>()
        val items = rows.mapNotNull { row ->
            val scrobble = runCatching { json.decodeFromString(Scrobble.serializer(), row.payload) }.getOrNull()
            if (scrobble == null) unreadable += row.id
            scrobble?.let { QueuedScrobble(row.id, service, it, row.attempts) }
        }
        if (unreadable.isNotEmpty()) dao.delete(unreadable)
        return items
    }

    override suspend fun oldestServerPlays(limit: Int): List<PendingServerPlay> = dao.oldestServerPlays(limit).map { row ->
        // An unreadable listen still tells the server; only this phone's accounts go without it.
        val scrobble = row.payload?.let { runCatching { json.decodeFromString(Scrobble.serializer(), it) }.getOrNull() }
        PendingServerPlay(row.id, row.serverId, row.trackId, row.playedAt, scrobble, row.attempts)
    }

    override suspend fun serverPlayFailed(id: Long) = dao.recordServerPlayFailure(id)

    override suspend fun release(play: PendingServerPlay, services: Set<ScrobbleService>) {
        val payload = play.scrobble?.let { json.encodeToString(Scrobble.serializer(), it) }
        val now = System.currentTimeMillis()
        val rows = if (payload == null) emptyList() else services.map { QueuedScrobbleEntity(service = it.name, payload = payload, createdAt = now) }
        dao.releaseServerPlay(play.id, rows)
    }

    override suspend fun remove(ids: Collection<Long>) {
        if (ids.isNotEmpty()) dao.delete(ids.toList())
    }

    override suspend fun recordFailure(ids: Collection<Long>) {
        if (ids.isNotEmpty()) dao.recordFailure(ids.toList())
    }
}

internal fun ScrobbleAccountEntity.toDomain(): ScrobbleAccount? {
    val type = ScrobbleService.entries.firstOrNull { it.name == service } ?: return null
    return ScrobbleAccount(type, username, secret, nowPlaying, needsReconnect)
}

internal fun ScrobbleAccount.toEntity() = ScrobbleAccountEntity(service.name, username, secret, nowPlaying, needsReconnect)
