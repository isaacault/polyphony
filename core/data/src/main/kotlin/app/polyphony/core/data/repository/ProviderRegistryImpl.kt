package app.polyphony.core.data.repository

import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerType
import app.polyphony.core.domain.provider.MediaProvider
import app.polyphony.core.domain.provider.MediaProviderFactory
import app.polyphony.core.domain.repository.ProviderRegistry
import app.polyphony.core.domain.repository.ServerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderRegistryImpl @Inject constructor(
    factories: Set<@JvmSuppressWildcards MediaProviderFactory>,
    private val serverRepository: ServerRepository,
) : ProviderRegistry {

    private val byType: Map<ServerType, MediaProviderFactory> = factories.associateBy { it.type }

    private class Cached(val server: Server, val provider: MediaProvider)

    private val cache = ConcurrentHashMap<String, Cached>()

    override val activeProvider: Flow<MediaProvider?> = serverRepository.activeServer
        .map { server -> server?.let(::forServer) }
        .distinctUntilChanged()

    override fun factoryFor(type: ServerType): MediaProviderFactory =
        byType[type] ?: throw IllegalStateException("No MediaProviderFactory registered for $type")

    override fun forServer(server: Server): MediaProvider {
        val cached = cache[server.id]
        if (cached != null && cached.server == server) return cached.provider
        val provider = factoryFor(server.type).create(server)
        cache[server.id] = Cached(server, provider)
        return provider
    }

    override suspend fun active(): MediaProvider? = serverRepository.getActive()?.let(::forServer)

    override fun invalidate(serverId: String) {
        cache.remove(serverId)
    }
}
