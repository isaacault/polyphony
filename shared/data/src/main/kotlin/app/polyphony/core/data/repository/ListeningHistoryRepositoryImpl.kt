package app.polyphony.core.data.repository

import app.polyphony.core.data.db.dao.PlayContextDao
import app.polyphony.core.data.mappers.toDomain
import app.polyphony.core.data.mappers.toEntity
import app.polyphony.core.domain.model.PlayContext
import app.polyphony.core.domain.model.RecentContext
import app.polyphony.core.domain.repository.ListeningHistoryRepository
import app.polyphony.core.domain.repository.ServerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ListeningHistoryRepositoryImpl @Inject constructor(
    private val dao: PlayContextDao,
    private val serverRepository: ServerRepository,
) : ListeningHistoryRepository {

    override fun recent(limit: Int): Flow<List<RecentContext>> =
        serverRepository.activeServer.map { it?.id }.distinctUntilChanged().flatMapLatest { sid ->
            if (sid == null) flowOf(emptyList())
            else dao.observeRecent(sid, limit).map { rows -> rows.mapNotNull { it.toDomain() } }
        }

    override suspend fun get(context: PlayContext): RecentContext? = dao.get(context.key)?.toDomain()

    override suspend fun record(entry: RecentContext) {
        dao.upsert(entry.toEntity())
        dao.prune(entry.context.serverId, KEEP_PER_SERVER)
    }

    override suspend fun remove(context: PlayContext) = dao.delete(context.key)

    override suspend fun clear() = dao.deleteAll()

    private companion object {
        const val KEEP_PER_SERVER = 50
    }
}
