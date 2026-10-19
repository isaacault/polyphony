package app.polyphony.provider.subsonic

import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerType
import app.polyphony.provider.subsonic.api.SubsonicApi
import app.polyphony.provider.subsonic.api.SubsonicUrlBuilder
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

internal val testJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

internal val testServer = Server(
    id = "srv-1",
    type = ServerType.SUBSONIC,
    name = "Test",
    baseUrl = "https://music.example.com",
    username = "alice",
    credential = "sesame",
    deviceId = "dev-1",
)

/** Routes requests to canned bodies by endpoint name; never touches the network. */
internal class FakeServer {
    private val routes = mutableMapOf<String, (HttpUrl) -> Pair<Int, String>>()
    val requests = mutableListOf<HttpUrl>()

    fun on(endpoint: String, body: String, status: Int = 200) = on(endpoint) { status to body }
    fun on(endpoint: String, handler: (HttpUrl) -> Pair<Int, String>) { routes[endpoint] = handler }

    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val url = chain.request().url
            requests += url
            val endpoint = url.pathSegments.last()
            val (status, body) = routes[endpoint]?.invoke(url) ?: (200 to failed(70, "Unknown endpoint $endpoint"))
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(if (status == 200) "OK" else "Error")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        })
        .build()

    fun provider(server: Server = testServer): SubsonicProvider =
        SubsonicProvider(server, SubsonicApi(client, testJson, SubsonicUrlBuilder.forServer(server)))

    fun lastRequest(endpoint: String): HttpUrl = requests.last { it.pathSegments.last() == endpoint }
}

internal fun ok(payload: String = ""): String {
    val extra = if (payload.isBlank()) "" else ",$payload"
    return """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.53.0 (13af8ed4)","openSubsonic":true$extra}}"""
}

internal fun failed(code: Int, message: String): String =
    """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":$code,"message":"$message"}}}"""
