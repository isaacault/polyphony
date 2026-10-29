package app.polyphony.ui.settings.servers

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.polyphony.R
import app.polyphony.core.domain.provider.ProviderException

/** Human-readable text for a failed connection attempt. */
@Composable
fun providerErrorMessage(error: Throwable): String = when (error) {
    is ProviderException.Unauthorized -> stringResource(R.string.settings_err_unauthorized)
    is ProviderException.NotFound -> stringResource(R.string.settings_err_not_found)
    is ProviderException.Network -> stringResource(R.string.settings_err_network)
    is ProviderException.Server -> stringResource(R.string.settings_err_server, error.code)
    is ProviderException.Unsupported -> stringResource(R.string.settings_err_unsupported)
    else -> error.message?.takeIf { it.isNotBlank() } ?: stringResource(R.string.settings_err_unknown)
}

/** Adds a scheme when missing and trims trailing slashes. */
fun normalizeBaseUrl(raw: String): String {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isEmpty()) return trimmed
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
}
