package app.polyphony.core.requests

import app.polyphony.core.domain.model.CatalogResults
import app.polyphony.core.domain.model.RequestOutcome
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.core.network.ServerHttpClientFactory
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContinuoRequestRepository internal constructor(
    private val client: ContinuoClient,
) : RequestRepository {

    @Inject
    constructor(clientFactory: ServerHttpClientFactory, json: Json, servers: ServerRepository) :
        this(ContinuoClient({ clientFactory.clientFor(it) }, json, servers))

    override suspend fun search(server: Server, query: String, limit: Int): CatalogResults {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val reply = client.get(server, "/v1/search?q=$q&types=album,track&limit=$limit")
        if (reply.code !in 200..299) throw client.serverError(reply, "continuo search failed")
        return client.decode(SearchDto.serializer(), reply.body).toDomain(artworkHost = hostOf(server.baseUrl))
    }

    override suspend fun requestAlbum(server: Server, releaseGroupId: String): RequestOutcome {
        val body = Json.encodeToString(CreateRequestBody.serializer(), CreateRequestBody("album", releaseGroupId))
        val reply = client.post(server, "/v1/requests", body)
        return when {
            reply.code in 200..299 -> RequestOutcome.Requested(client.decode(CreatedRequestDto.serializer(), reply.body).id)
            reply.code == 409 -> {
                val err = runCatching { client.decode(ErrorEnvelope.serializer(), reply.body).error }.getOrNull()
                when (err?.code) {
                    "in_library" -> RequestOutcome.InLibrary(err?.navidromeAlbumId)
                    "already_requested" -> RequestOutcome.AlreadyRequested(err?.requestId)
                    else -> throw client.serverError(reply, "continuo refused the request")
                }
            }
            else -> throw client.serverError(reply, "continuo couldn't request that album")
        }
    }
}
