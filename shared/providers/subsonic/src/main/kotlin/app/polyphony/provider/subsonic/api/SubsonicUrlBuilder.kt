package app.polyphony.provider.subsonic.api

import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerOptions
import app.polyphony.provider.subsonic.internal.md5Hex
import app.polyphony.provider.subsonic.internal.sha256Hex
import app.polyphony.provider.subsonic.internal.toHex
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Builds `/rest/<endpoint>` URLs with the standard Subsonic parameters.
 *
 * The salt is fixed per [Server] (derived from its id and device id) so that stream and artwork URLs
 * are stable across calls and app restarts: Coil's cache and the player's stream cache are keyed
 * by URL. A per-request salt would make every artwork request a cache miss.
 */
internal class SubsonicUrlBuilder(
    val baseUrl: String,
    private val username: String,
    private val password: String,
    private val legacyAuth: Boolean,
    private val salt: String,
) {
    private val token: String = md5Hex(password + salt)
    private val encodedPassword: String = "enc:" + password.toByteArray(Charsets.UTF_8).toHex()

    fun build(endpoint: String, params: Map<String, Any?> = emptyMap()): HttpUrl {
        val builder = "$baseUrl/rest/$endpoint".toHttpUrl().newBuilder()
        builder.addQueryParameter("u", username)
        if (legacyAuth) {
            builder.addQueryParameter("p", encodedPassword)
        } else {
            builder.addQueryParameter("t", token)
            builder.addQueryParameter("s", salt)
        }
        builder.addQueryParameter("v", API_VERSION)
        builder.addQueryParameter("c", CLIENT_NAME)
        builder.addQueryParameter("f", "json")
        params.forEach { (key, value) ->
            when (value) {
                null -> Unit
                is Iterable<*> -> value.forEach { item -> item?.let { builder.addQueryParameter(key, it.toString()) } }
                else -> builder.addQueryParameter(key, value.toString())
            }
        }
        return builder.build()
    }

    fun buildString(endpoint: String, params: Map<String, Any?> = emptyMap()): String = build(endpoint, params).toString()

    companion object {
        const val API_VERSION = "1.16.1"
        const val CLIENT_NAME = "Polyphony"

        fun forServer(server: Server): SubsonicUrlBuilder = SubsonicUrlBuilder(
            baseUrl = normalizeBaseUrl(server.baseUrl),
            username = server.username,
            password = server.credential,
            legacyAuth = server.options[ServerOptions.LEGACY_AUTH] == "true",
            salt = stableSalt(server),
        )

        fun stableSalt(server: Server): String = sha256Hex("${server.id}:${server.deviceId}:polyphony").take(16)

        /**
         * Accepts what people type: adds `https://` when the scheme is missing, drops trailing
         * slashes and a trailing `/rest`. Throws [IllegalArgumentException] for garbage input.
         */
        fun normalizeBaseUrl(input: String): String {
            var url = input.trim()
            require(url.isNotEmpty()) { "Server address is empty" }
            if (!url.contains("://")) url = "https://$url"
            url = url.trimEnd('/')
            if (url.endsWith("/rest", ignoreCase = true)) url = url.dropLast(5).trimEnd('/')
            val parsed = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid server address")
            require(parsed.scheme == "http" || parsed.scheme == "https") { "Only http and https are supported" }
            return parsed.toString().trimEnd('/')
        }
    }
}
