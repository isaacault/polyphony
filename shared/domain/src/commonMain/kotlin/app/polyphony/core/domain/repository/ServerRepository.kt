package app.polyphony.core.domain.repository

import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerType
import app.polyphony.core.domain.provider.MediaProvider
import app.polyphony.core.domain.provider.MediaProviderFactory
import kotlinx.coroutines.flow.Flow

interface ServerRepository {
    val servers: Flow<List<Server>>
    val activeServer: Flow<Server?>
    suspend fun getActive(): Server?
    suspend fun get(id: String): Server?
    suspend fun add(server: Server, makeActive: Boolean = true)
    suspend fun update(server: Server)
    suspend fun remove(serverId: String)
    suspend fun setActive(serverId: String)
}

/** Resolves [MediaProvider] instances for servers and exposes the one for the active server. */
interface ProviderRegistry {
    val activeProvider: Flow<MediaProvider?>
    fun factoryFor(type: ServerType): MediaProviderFactory
    fun forServer(server: Server): MediaProvider
    suspend fun active(): MediaProvider?
    /** Drop cached providers (after credentials change). */
    fun invalidate(serverId: String)
}
