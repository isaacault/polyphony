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

    /** True when this server is a continuo server ([ServerOptions.CONTINUO]). */
    val isContinuo: Boolean
        get() = options[ServerOptions.CONTINUO] == "true"

    /**
     * A continuo server with no app password for this install, e.g. one restored from a backup
     * ([withoutContinuoSession]): it needs the continuo password once to sign this phone in as a
     * new device.
     */
    val needsContinuoSignIn: Boolean
        get() = isContinuo && credential.isEmpty()

    /**
     * This server without its continuo device: no app password, device id or refresh token, but
     * still marked continuo so the app asks for a fresh sign-in. A continuo session belongs to one
     * install; a copy used on a second phone would present already-rotated refresh tokens, and
     * continuo revokes the device for both. Any other server is returned unchanged.
     */
    fun withoutContinuoSession(): Server =
        if (!isContinuo) this
        else copy(
            credential = "",
            options = options - ServerOptions.CONTINUO_DEVICE_ID - ServerOptions.CONTINUO_REFRESH_TOKEN,
        )
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

    /** Comma-separated [app.polyphony.core.domain.transcode.AudioCodec] names the server can transcode to (probed). */
    const val TRANSCODE_CODECS = "transcodeCodecs"

    /** Epoch millis of the last transcoding probe. */
    const val TRANSCODE_PROBED_AT = "transcodeProbedAt"

    /**
     * "true" when [Server.baseUrl] is a continuo server (the Subsonic façade in front of Navidrome).
     * Its [Server.credential] is then this install's per-device app password, never the account
     * password.
     */
    const val CONTINUO = "continuo"

    /** continuo's id for this install (`dev_…`); revoking it there cuts off requests and playback. */
    const val CONTINUO_DEVICE_ID = "continuoDeviceId"

    /** continuo refresh token for this install's account session (rotated on every use). */
    const val CONTINUO_REFRESH_TOKEN = "continuoRefreshToken"

    /** Keys a fresh continuo sign-in replaces; an edit must not keep the previous device's values. */
    val CONTINUO_KEYS = setOf(CONTINUO, CONTINUO_DEVICE_ID, CONTINUO_REFRESH_TOKEN)
}

data class ServerInfo(
    val productName: String,
    val version: String,
    val apiVersion: String? = null,
    val isOpenSubsonic: Boolean = false,
    val extensions: List<String> = emptyList(),
)
