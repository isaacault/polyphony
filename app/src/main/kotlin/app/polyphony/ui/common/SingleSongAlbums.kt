package app.polyphony.ui.common

import app.polyphony.core.data.di.ApplicationScope
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.ServerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The library albums that hold single songs rather than the album (requested or kept one at a
 * time; continuo files them under _singles), by album id. Albums and Artists leave them out unless
 * asked; Songs can show only them. Fetched when the server changes and when the Library opens.
 */
@Singleton
class SingleSongAlbums @Inject constructor(
    private val servers: ServerRepository,
    private val requests: RequestRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    init {
        scope.launch { servers.activeServer.map { it?.id }.distinctUntilChanged().collect { load() } }
    }

    fun refresh() {
        scope.launch { load() }
    }

    private suspend fun load() {
        val server = servers.getActive()?.takeIf { requests.supports(it) }
        _ids.value = if (server == null) emptySet() else try {
            requests.singleSongAlbums(server) ?: emptySet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _ids.value
        }
    }
}
