package app.polyphony.core.playback.engine

import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.provider.MediaProvider
import app.polyphony.core.domain.repository.ProviderRegistry
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.core.playback.di.PlaybackScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps a hot map of servers so playback code can resolve a [MediaProvider] synchronously
 * (needed inside Media3 callbacks that cannot suspend).
 */
@Singleton
class ServerLookup @Inject constructor(
    private val serverRepository: ServerRepository,
    private val providerRegistry: ProviderRegistry,
    @PlaybackScope scope: CoroutineScope,
) {
    val servers: StateFlow<Map<String, Server>> = serverRepository.servers
        .map { list -> list.associateBy { it.id } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    fun serverFor(serverId: String): Server? = servers.value[serverId]

    fun providerFor(serverId: String): MediaProvider? = serverFor(serverId)?.let(providerRegistry::forServer)

    suspend fun awaitProvider(serverId: String): MediaProvider? {
        providerFor(serverId)?.let { return it }
        return serverRepository.get(serverId)?.let(providerRegistry::forServer)
    }
}
