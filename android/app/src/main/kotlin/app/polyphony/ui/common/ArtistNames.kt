package app.polyphony.ui.common

import app.polyphony.core.data.di.ApplicationScope
import app.polyphony.core.domain.model.ArtistName
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.ServerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Artists the library knows by a Latin name with an original in another script (Ichiko Aoba /
 * 青葉市子, Fishmans / フィッシュマンズ), from the active continuo server: artist pages show
 * both, and search finds the artist by either. Empty on other servers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ArtistNames @Inject constructor(
    servers: ServerRepository,
    requests: RequestRepository,
    @ApplicationScope scope: CoroutineScope,
) {
    val names: StateFlow<List<ArtistName>> = servers.activeServer.map { it?.id }.distinctUntilChanged()
        .mapLatest {
            val server = servers.getActive()?.takeIf { requests.supports(it) } ?: return@mapLatest emptyList()
            runCatching { requests.artistNames(server) }.getOrDefault(emptyList())
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The original name of library artist [name], if it has one. */
    fun originalOf(name: String?): String? =
        name?.let { n -> names.value.firstOrNull { it.name.equals(n, ignoreCase = true) }?.originalName }

    companion object {
        /** Library names whose original name contains [query] (the script the user typed). */
        fun matching(names: List<ArtistName>, query: String): List<String> {
            val q = query.trim()
            if (q.isEmpty()) return emptyList()
            return names.filter { it.originalName.contains(q, ignoreCase = true) }.map { it.name }.distinct()
        }
    }
}
