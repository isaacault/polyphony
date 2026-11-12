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
import app.polyphony.core.domain.provider.QuickConnectFactory
import app.polyphony.core.domain.provider.QuickConnectSignIn
import app.polyphony.core.network.ServerHttpClientFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.jellyfin.sdk.model.api.QuickConnectDto
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
) : MediaProviderFactory, QuickConnectFactory {

    override val type: ServerType = ServerType.JELLYFIN

    override suspend fun authenticate(request: ConnectRequest): Server = jellyfinCall {
        val sign = signInTo(request)
        val info = sign.api.systemApi.getPublicSystemInfo().content
        val auth = try {
            sign.api.authenticationApi.authenticateUserByName(request.username, request.password).content
        } catch (e: Throwable) {
            val mapped = e.toProviderException()
            throw if (mapped is ProviderException.Unauthorized) {
                ProviderException.Unauthorized("Invalid username or password", e)
            } else mapped
        }
        serverFor(request, sign, info, auth)
    }

    override suspend fun startQuickConnect(request: ConnectRequest): QuickConnectSignIn = jellyfinCall {
        val sign = signInTo(request)
        val info = sign.api.systemApi.getPublicSystemInfo().content
        if (!sign.api.authenticationApi.getQuickConnectEnabled().content) {
            throw ProviderException.Unsupported("Quick Connect is turned off on this server")
        }
        val started = sign.api.authenticationApi.initiateQuickConnect().content
        object : QuickConnectSignIn {
            override val code: String = started.code

            override suspend fun awaitServer(): Server = jellyfinCall {
                var blips = 0
                while (true) {
                    val state = try {
                        sign.api.authenticationApi.getQuickConnectState(started.secret).content.also { blips = 0 }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        val mapped = e.toProviderException()
                        // Jellyfin forgets a code that wasn't approved in time. Said as such, not as
                        // "wrong password", which this sign-in never had.
                        if (mapped is ProviderException.NotFound || mapped is ProviderException.Unauthorized) {
                            throw ProviderException.Unknown("the Quick Connect code expired before it was approved; start again", e)
                        }
                        // A moment without the network doesn't end a sign-in that's waiting on a person.
                        if (mapped is ProviderException.Network && ++blips < QUICK_CONNECT_BLIPS) null else throw mapped
                    }
                    if (state?.authenticated == true) break
                    delay(QUICK_CONNECT_POLL_MS)
                }
                val auth = sign.api.authenticationApi.authenticateWithQuickConnect(QuickConnectDto(secret = started.secret)).content
                serverFor(request, sign, info, auth)
            }
        }
    }

    /** A sign-in's address and client: a new device id, the server's own HTTP options. */
    private class SignIn(val baseUrl: String, val deviceId: String, val api: ApiClient)

    private fun signInTo(request: ConnectRequest): SignIn {
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
        return SignIn(baseUrl, deviceId, api)
    }

    private fun serverFor(request: ConnectRequest, sign: SignIn, info: PublicSystemInfo, auth: AuthenticationResult): Server {
        val baseUrl = sign.baseUrl
        val token = auth.accessToken?.takeIf { it.isNotBlank() }
            ?: throw ProviderException.Unauthorized("Jellyfin did not return an access token")
        val user = auth.user ?: throw ProviderException.Unknown("Jellyfin did not return the user profile")

        return Server(
            id = UUID.randomUUID().toString(),
            type = ServerType.JELLYFIN,
            name = request.name.ifBlank { info.serverName?.takeIf { it.isNotBlank() } ?: baseUrl.removePrefix("https://").removePrefix("http://") },
            baseUrl = baseUrl,
            username = user.name ?: request.username,
            credential = token,
            userId = user.id.toString(),
            deviceId = sign.deviceId,
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
        /** How often a Quick Connect code is checked for approval, as Jellyfin's own clients do. */
        const val QUICK_CONNECT_POLL_MS = 5_000L
        /** Network failures in a row a waiting Quick Connect sign-in rides out. */
        const val QUICK_CONNECT_BLIPS = 6
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class JellyfinProviderModule {
    @Binds
    @IntoSet
    abstract fun bindJellyfinProviderFactory(factory: JellyfinProviderFactory): MediaProviderFactory
}
