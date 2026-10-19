package app.polyphony.core.network

import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Derives per-server HTTP clients from the shared base client (same pool, same dispatcher),
 * layering in custom headers and, when the user opted in, trust for self-signed certificates.
 */
@Singleton
class ServerHttpClientFactory @Inject constructor(
    private val baseClient: OkHttpClient,
    private val json: Json,
) {
    private val cache = ConcurrentHashMap<String, OkHttpClient>()

    fun clientFor(server: Server): OkHttpClient =
        cache.getOrPut(cacheKey(server.id, server.options)) { build(server.options) }

    /** For connection attempts before a [Server] exists. */
    fun clientFor(options: Map<String, String>): OkHttpClient = build(options)

    fun invalidate(serverId: String) {
        cache.keys.filter { it.startsWith("$serverId|") }.forEach(cache::remove)
    }

    fun customHeaders(options: Map<String, String>): Map<String, String> {
        val raw = options[ServerOptions.CUSTOM_HEADERS]?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return runCatching {
            json.parseToJsonElement(raw).jsonObject.mapValues { it.value.jsonPrimitive.content }
        }.getOrDefault(emptyMap())
    }

    private fun cacheKey(serverId: String, options: Map<String, String>) =
        "$serverId|${options[ServerOptions.CUSTOM_HEADERS].hashCode()}|${options[ServerOptions.TRUST_SELF_SIGNED]}"

    private fun build(options: Map<String, String>): OkHttpClient {
        val headers = customHeaders(options)
        val trustAll = options[ServerOptions.TRUST_SELF_SIGNED] == "true"
        if (headers.isEmpty() && !trustAll) return baseClient
        val builder = baseClient.newBuilder()
        if (headers.isNotEmpty()) {
            builder.addInterceptor(Interceptor { chain ->
                val request = chain.request().newBuilder()
                headers.forEach { (k, v) -> request.header(k, v) }
                chain.proceed(request.build())
            })
        }
        if (trustAll) {
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), SecureRandom()) }
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)
            builder.hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }
}
