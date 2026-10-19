package app.polyphony.provider.jellyfin

import android.content.Context
import android.os.Build
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerOptions
import app.polyphony.core.domain.model.ServerType
import app.polyphony.core.domain.provider.ConnectRequest
import app.polyphony.core.domain.provider.MediaProvider
import app.polyphony.core.domain.provider.MediaProviderFactory
import app.polyphony.core.domain.provider.ProviderException
import app.polyphony.core.network.ServerHttpClientFactory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import okhttp3.OkHttpClient
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Creates [JellyfinMediaProvider]s. Each provider gets its own SDK `ApiClient` backed by the
 * per-server OkHttp client from [ServerHttpClientFactory], so custom headers and the
 * "trust self-signed" option apply to every Jellyfin request.
 */
@Singleton
class JellyfinProviderFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClientFactory: ServerHttpClientFactory,
) : MediaProviderFactory {

    override val type: ServerType = ServerType.JELLYFIN

    override suspend fun authenticate(request: ConnectRequest): Server = jellyfinCall {
        val baseUrl = try {
            JellyfinUrlNormalizer.normalize(request.baseUrl, type.defaultPort ?: 8096)
        } catch (e: IllegalArgumentException) {
            throw ProviderException.Unknown(e.message ?: "Invalid server address", e)
        }
        val deviceId = UUID.randomUUID().toString()
        val api = createApi(
            baseUrl = baseUrl,
            accessToken = null,
            deviceId = deviceId,
            http = httpClientFactory.clientFor(request.options),
        )

        val info = api.systemApi.getPublicSystemInfo().content
        val auth = try {
            api.authenticationApi.authenticateUserByName(request.username, request.password).content
        } catch (e: Throwable) {
            val mapped = e.toProviderException()
            throw if (mapped is ProviderException.Unauthorized) {
                ProviderException.Unauthorized("Invalid username or password", e)
            } else mapped
        }
        val token = auth.accessToken?.takeIf { it.isNotBlank() }
            ?: throw ProviderException.Unauthorized("Jellyfin did not return an access token")
        val user = auth.user ?: throw ProviderException.Unknown("Jellyfin did not return the user profile")

        Server(
            id = UUID.randomUUID().toString(),
            type = ServerType.JELLYFIN,
            name = request.name.ifBlank { info.serverName?.takeIf { it.isNotBlank() } ?: baseUrl.removePrefix("https://").removePrefix("http://") },
            baseUrl = baseUrl,
            username = user.name ?: request.username,
            credential = token,
            userId = user.id.toString(),
            deviceId = deviceId,
            options = request.options + (ServerOptions.SERVER_VERSION to listOfNotNull(info.productName ?: "Jellyfin", info.version).joinToString(" ")),
            createdAt = System.currentTimeMillis(),
        )
    }

    override fun create(server: Server): MediaProvider {
        val api = createApi(
            baseUrl = server.baseUrl,
            accessToken = server.credential,
            deviceId = server.deviceId,
            http = httpClientFactory.clientFor(server),
        )
        return JellyfinMediaProvider(server, api)
    }

    private fun createApi(baseUrl: String, accessToken: String?, deviceId: String, http: OkHttpClient): ApiClient {
        val jellyfin = createJellyfin {
            context = this@JellyfinProviderFactory.context
            clientInfo = CLIENT_INFO
            deviceInfo = DeviceInfo(id = deviceId, name = deviceName())
            apiClientFactory = OkHttpFactory(http)
        }
        return jellyfin.createApi(
            baseUrl = baseUrl,
            accessToken = accessToken,
            httpClientOptions = HttpClientOptions(
                connectTimeout = 15.seconds,
                requestTimeout = 120.seconds,
                socketTimeout = 120.seconds,
            ),
        )
    }

    private fun deviceName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val model = Build.MODEL.orEmpty()
        return if (model.startsWith(manufacturer, ignoreCase = true) || manufacturer.isBlank()) model.ifBlank { "Android" }
        else "$manufacturer $model"
    }

    companion object {
        const val CLIENT_NAME = "Polyphony"
        const val CLIENT_VERSION = "0.1.0"
        val CLIENT_INFO = ClientInfo(name = CLIENT_NAME, version = CLIENT_VERSION)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class JellyfinProviderModule {
    @Binds
    @IntoSet
    abstract fun bindJellyfinProviderFactory(factory: JellyfinProviderFactory): MediaProviderFactory
}
