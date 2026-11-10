package app.polyphony.core.scrobble.queue

import app.polyphony.core.domain.model.ScrobbleAccount
import app.polyphony.core.domain.model.ScrobbleService
import app.polyphony.core.scrobble.Scrobble
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
