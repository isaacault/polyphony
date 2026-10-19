package app.polyphony.core.domain.model

import kotlinx.serialization.Serializable

/** Kinds of music servers Polyphony can talk to. */
@Serializable
enum class ServerType(val displayName: String, val defaultPort: Int?) {
    SUBSONIC("Subsonic / Navidrome / OpenSubsonic", 4533),
    JELLYFIN("Jellyfin", 8096),
}

/**
 * A configured media server. Credentials are stored app-private; [credential] is the password
 * for Subsonic-style servers and the access token for Jellyfin.
 */
@Serializable
data class Server(
    val id: String,
    val type: ServerType,
    val name: String,
    val baseUrl: String,
    val username: String,
    val credential: String,
    val userId: String? = null,
    val deviceId: String,
    val options: Map<String, String> = emptyMap(),
    val createdAt: Long = 0L,
) {
    val displayHost: String
        get() = baseUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')
}

/** Keys used inside [Server.options]. */
object ServerOptions {
    /** "true" to send plain-text password auth (`p=`) instead of salted token auth. Subsonic only. */
    const val LEGACY_AUTH = "legacyAuth"

    /** JSON object of extra HTTP headers to send with every request (reverse proxies, Cloudflare Access). */
    const val CUSTOM_HEADERS = "customHeaders"

    /** "true" to trust self-signed certificates for this server. */
    const val TRUST_SELF_SIGNED = "trustSelfSigned"

    /** Jellyfin only: the server's reported product name/version at login time. */
    const val SERVER_VERSION = "serverVersion"

    /**
     * Optional base URL handed to Cast / DLNA receivers instead of [Server.baseUrl], e.g. a LAN
     * address when the public hostname does not resolve or route from the receiver.
     */
    const val REMOTE_BASE_URL = "remoteBaseUrl"

    /** "false" to stop substituting the phone-resolved LAN IP into receiver URLs. Default on. */
    const val REMOTE_AUTO_LAN = "remoteAutoLan"
}

data class ServerInfo(
    val productName: String,
    val version: String,
    val apiVersion: String? = null,
    val isOpenSubsonic: Boolean = false,
    val extensions: List<String> = emptyList(),
)
