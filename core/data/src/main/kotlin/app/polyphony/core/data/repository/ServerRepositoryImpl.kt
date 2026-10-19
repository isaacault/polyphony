package app.polyphony.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.polyphony.core.data.db.PolyphonyDatabase
import app.polyphony.core.data.mappers.toDomain
import app.polyphony.core.data.mappers.toEntity
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.repository.ProviderRegistry
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.core.network.ServerHttpClientFactory
import dagger.Lazy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerRepositoryImpl @Inject constructor(
    private val db: PolyphonyDatabase,
    private val prefs: DataStore<Preferences>,
    private val httpClientFactory: ServerHttpClientFactory,
    private val providerRegistry: Lazy<ProviderRegistry>,
) : ServerRepository {

    private val serverDao = db.serverDao()

    override val servers: Flow<List<Server>> = serverDao.observeAll()
        .map { list -> list.map { it.toDomain() } }
        .distinctUntilChanged()

    /** The stored active id, falling back to the first server so the app never has servers but no active one. */
    override val activeServer: Flow<Server?> = combine(
        servers,
        prefs.data.map { it[ACTIVE_SERVER_ID] }.distinctUntilChanged(),
    ) { all, activeId ->
        all.firstOrNull { it.id == activeId } ?: all.firstOrNull()
    }.distinctUntilChanged()

    override suspend fun getActive(): Server? = activeServer.first()

    override suspend fun get(id: String): Server? = serverDao.get(id)?.toDomain()

    override suspend fun add(server: Server, makeActive: Boolean) {
        val stamped = if (server.createdAt == 0L) server.copy(createdAt = System.currentTimeMillis()) else server
        serverDao.upsert(stamped.toEntity())
        val currentActive = prefs.data.first()[ACTIVE_SERVER_ID]
        if (makeActive || currentActive == null) setActive(stamped.id)
    }

    override suspend fun update(server: Server) {
        serverDao.upsert(server.toEntity())
        httpClientFactory.invalidate(server.id)
        providerRegistry.get().invalidate(server.id)
    }

    override suspend fun remove(serverId: String) {
        db.clearServerData(serverId)
        serverDao.delete(serverId)
        httpClientFactory.invalidate(serverId)
        providerRegistry.get().invalidate(serverId)
        val remaining = serverDao.getAll()
        val active = prefs.data.first()[ACTIVE_SERVER_ID]
        if (active == serverId) {
            prefs.edit { p ->
                val next = remaining.firstOrNull()?.id
                if (next == null) p.remove(ACTIVE_SERVER_ID) else p[ACTIVE_SERVER_ID] = next
            }
        }
    }

    override suspend fun setActive(serverId: String) {
        prefs.edit { it[ACTIVE_SERVER_ID] = serverId }
    }

    private companion object {
        val ACTIVE_SERVER_ID = stringPreferencesKey("active_server_id")
    }
}
