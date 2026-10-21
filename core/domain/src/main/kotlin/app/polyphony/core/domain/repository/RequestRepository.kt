package app.polyphony.core.domain.repository

import app.polyphony.core.domain.model.CatalogResults
import app.polyphony.core.domain.model.MusicRequest
import app.polyphony.core.domain.model.RequestAction
import app.polyphony.core.domain.model.RequestEvent
import app.polyphony.core.domain.model.RequestOutcome
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerOptions
import kotlinx.coroutines.flow.Flow

/**
 * Acquisition through a continuo server: its catalogue ("Everything") and album requests. Only
 * continuo-linked servers support it; every caller checks [supports] and shows nothing otherwise.
 * Failures are [app.polyphony.core.domain.provider.ProviderException]s.
 */
interface RequestRepository {
    fun supports(server: Server?): Boolean = server?.options?.get(ServerOptions.CONTINUO) == "true"

    /** Library and catalogue hits merged by the server, each with its availability. */
    suspend fun search(server: Server, query: String, limit: Int = 25): CatalogResults

    /** Request the album (MusicBrainz release group). */
    suspend fun requestAlbum(server: Server, releaseGroupId: String): RequestOutcome

    /** Requests visible to this account (admins see everyone's), newest first, live pipelines. */
    suspend fun requests(server: Server, activeOnly: Boolean = false): List<MusicRequest>

    /** Live updates while collected; reconnects on its own and emits [RequestEvent.Resync] after. */
    fun events(server: Server): Flow<RequestEvent>

    /** Run [action] on a request; returns continuo's one-line confirmation. */
    suspend fun perform(server: Server, requestId: Long, action: RequestAction): String
}
