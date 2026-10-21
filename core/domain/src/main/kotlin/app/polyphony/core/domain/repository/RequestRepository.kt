package app.polyphony.core.domain.repository

import app.polyphony.core.domain.model.CatalogResults
import app.polyphony.core.domain.model.RequestOutcome
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerOptions

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
}
