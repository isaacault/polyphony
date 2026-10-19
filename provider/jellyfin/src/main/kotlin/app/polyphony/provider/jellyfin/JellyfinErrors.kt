package app.polyphony.provider.jellyfin

import app.polyphony.core.domain.provider.ProviderException
import kotlinx.coroutines.CancellationException
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidContentException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.SecureConnectionException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import java.io.IOException

/** Runs [block] and converts SDK / transport failures into [ProviderException]s. */
internal inline fun <T> jellyfinCall(block: () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: ProviderException) {
    throw e
} catch (e: Throwable) {
    throw e.toProviderException()
}

internal fun Throwable.toProviderException(): ProviderException = when (this) {
    is ProviderException -> this
    is InvalidStatusException -> when (status) {
        401, 403 -> ProviderException.Unauthorized("Jellyfin rejected the credentials (HTTP $status)", this)
        404 -> ProviderException.NotFound("Not found on the Jellyfin server", this)
        in 500..599 -> ProviderException.Server(status, "Jellyfin server error (HTTP $status)", this)
        else -> ProviderException.Server(status, "Unexpected response from Jellyfin (HTTP $status)", this)
    }
    is TimeoutException -> ProviderException.Network(message ?: "Could not reach the Jellyfin server", this)
    is SecureConnectionException -> ProviderException.Network(
        "Secure connection failed. Check the certificate or enable \"trust self-signed\".", this,
    )
    is InvalidContentException -> ProviderException.Unknown("Jellyfin returned an unexpected response", this)
    is ApiClientException -> ProviderException.Network(message ?: "Network error", this)
    is IOException -> ProviderException.Network(message ?: "Network error", this)
    else -> ProviderException.Unknown(message ?: "Unexpected error", this)
}
