package app.polyphony.provider.subsonic

import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerOptions
import app.polyphony.core.domain.model.ServerType
import app.polyphony.core.domain.provider.ConnectRequest
import app.polyphony.core.domain.provider.MediaProvider
import app.polyphony.core.domain.provider.MediaProviderFactory
import app.polyphony.core.domain.provider.ProviderException
import app.polyphony.core.network.ServerHttpClientFactory
import app.polyphony.provider.subsonic.api.SubsonicApi
import app.polyphony.provider.subsonic.api.SubsonicApiException
import app.polyphony.provider.subsonic.api.SubsonicUrlBuilder
import app.polyphony.provider.subsonic.api.toProviderException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubsonicProviderFactory @Inject constructor(
    private val clientFactory: ServerHttpClientFactory,
    private val json: Json,
) : MediaProviderFactory {

    override val type: ServerType = ServerType.SUBSONIC

    override suspend fun authenticate(request: ConnectRequest): Server {
        val baseUrl = try {
            SubsonicUrlBuilder.normalizeBaseUrl(request.baseUrl)
        } catch (e: IllegalArgumentException) {
            throw ProviderException.Network(e.message ?: "Invalid server address", e)
        }
        if (request.username.isBlank()) throw ProviderException.Unauthorized("Username is required")

        val client = clientFactory.clientFor(request.options)
        val serverId = UUID.randomUUID().toString()
        val deviceId = UUID.randomUUID().toString()
        val legacyRequested = request.options[ServerOptions.LEGACY_AUTH] == "true"

        fun candidate(legacy: Boolean) = Server(
            id = serverId,
            type = type,
            name = request.name.ifBlank { baseUrl.removePrefix("https://").removePrefix("http://") },
            baseUrl = baseUrl,
            username = request.username.trim(),
            credential = request.password,
            deviceId = deviceId,
            options = request.options + (ServerOptions.LEGACY_AUTH to legacy.toString()),
            createdAt = System.currentTimeMillis(),
        )

        var server = candidate(legacyRequested)
        val info = try {
            createProvider(server, client).ping()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val cause = e.cause
            val tokenUnsupported = !legacyRequested &&
                (cause is SubsonicApiException && cause.code == SubsonicApiException.TOKEN_AUTH_NOT_SUPPORTED)
            if (!tokenUnsupported) throw e.toProviderException()
            server = candidate(legacy = true)
            createProvider(server, client).ping()
        }
        return server.copy(
            options = server.options + (ServerOptions.SERVER_VERSION to "${info.productName} ${info.version}".trim()),
        )
    }

    override fun create(server: Server): MediaProvider = createProvider(server, clientFactory.clientFor(server))

    private fun createProvider(server: Server, callFactory: Call.Factory): SubsonicProvider =
        SubsonicProvider(server, SubsonicApi(callFactory, json, SubsonicUrlBuilder.forServer(server)))
}
