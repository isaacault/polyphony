package app.polyphony.provider.subsonic.api

import app.polyphony.core.domain.provider.ProviderException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import java.io.IOException

/** A `status: failed` envelope from the server. */
internal class SubsonicApiException(val code: Int, message: String) : Exception(message) {
    companion object {
        const val GENERIC = 0
        const val MISSING_PARAMETER = 10
        const val CLIENT_MUST_UPGRADE = 20
        const val SERVER_MUST_UPGRADE = 30
        const val WRONG_CREDENTIALS = 40
        const val TOKEN_AUTH_NOT_SUPPORTED = 41
        const val NOT_AUTHORIZED = 50
        const val TRIAL_EXPIRED = 60
        const val NOT_FOUND = 70
    }
}

/** Non-2xx HTTP status before any Subsonic envelope could be read. */
internal class SubsonicHttpException(val status: Int, message: String) : Exception(message)

/** The response was 2xx but not a Subsonic envelope (wrong URL, captive portal, proxy page). */
internal class SubsonicProtocolException(message: String) : Exception(message)

internal fun Throwable.toProviderException(): ProviderException = when (this) {
    is ProviderException -> this
    is SubsonicApiException -> when (code) {
        SubsonicApiException.WRONG_CREDENTIALS -> ProviderException.Unauthorized(message ?: "Wrong username or password", this)
        SubsonicApiException.TOKEN_AUTH_NOT_SUPPORTED ->
            ProviderException.Unauthorized("Token authentication is not supported by this server; enable legacy authentication", this)
        SubsonicApiException.NOT_AUTHORIZED -> ProviderException.Unauthorized(message ?: "Not authorised for this operation", this)
        SubsonicApiException.NOT_FOUND -> ProviderException.NotFound(message ?: "Not found", this)
        SubsonicApiException.CLIENT_MUST_UPGRADE,
        SubsonicApiException.SERVER_MUST_UPGRADE -> ProviderException.Unsupported(message ?: "Incompatible server version")
        else -> ProviderException.Server(code, message ?: "Server error $code", this)
    }
    is SubsonicHttpException -> when (status) {
        401, 403 -> ProviderException.Unauthorized("Server rejected the request (HTTP $status)", this)
        404 -> ProviderException.NotFound("Subsonic API not found at this address (HTTP 404)", this)
        else -> ProviderException.Server(status, "HTTP $status ${message.orEmpty()}".trim(), this)
    }
    is SubsonicProtocolException -> ProviderException.Unknown(message ?: "Unexpected response", this)
    is SerializationException -> ProviderException.Unknown("Unexpected response from server: ${message.orEmpty().take(120)}", this)
    is IOException -> ProviderException.Network(message ?: "Network error", this)
    is IllegalArgumentException -> ProviderException.Unknown(message ?: "Invalid request", this)
    else -> ProviderException.Unknown(message ?: "Unknown error", this)
}

/** Runs [block], translating every failure into a [ProviderException]. Cancellation passes through. */
internal inline fun <T> subsonicCall(block: () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    throw e.toProviderException()
}
