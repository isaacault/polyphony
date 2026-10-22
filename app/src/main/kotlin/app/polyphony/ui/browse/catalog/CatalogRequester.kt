package app.polyphony.ui.browse.catalog

import app.polyphony.R
import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.RequestOutcome
import app.polyphony.core.domain.provider.ProviderException
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.ui.common.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Album requests from a screen that shows catalogue items (#50). [requested] holds what this
 * screen asked for, keyed by MusicBrainz release group so an album and its tracks change together;
 * [requesting] the requests still in flight. Outcomes and errors go to [messages] (the snackbar).
 */
class CatalogRequester(
    private val scope: CoroutineScope,
    private val servers: ServerRepository,
    private val requests: RequestRepository,
    private val messages: SendChannel<UiMessage>,
) {
    private val _requested = MutableStateFlow<Map<String, Availability>>(emptyMap())
    val requested: StateFlow<Map<String, Availability>> = _requested.asStateFlow()
    private val _requesting = MutableStateFlow<Set<String>>(emptySet())
    val requesting: StateFlow<Set<String>> = _requesting.asStateFlow()

    /** Request the album with this MusicBrainz release group (an album row, or a track's album). */
    fun request(releaseGroupId: String) {
        if (releaseGroupId in _requesting.value) return
        _requesting.update { it + releaseGroupId }
        scope.launch {
            try {
                val server = servers.getActive()?.takeIf { requests.supports(it) } ?: return@launch
                when (val outcome = requests.requestAlbum(server, releaseGroupId)) {
                    is RequestOutcome.Requested -> {
                        _requested.update { it + (releaseGroupId to Availability.Requested(outcome.requestId, "requested")) }
                        messages.send(UiMessage(R.string.browse_request_sent))
                    }
                    is RequestOutcome.AlreadyRequested -> {
                        _requested.update { it + (releaseGroupId to Availability.Requested(outcome.requestId, "requested")) }
                        messages.send(UiMessage(R.string.browse_request_already))
                    }
                    is RequestOutcome.InLibrary -> {
                        _requested.update { it + (releaseGroupId to Availability.InLibrary(outcome.libraryAlbumId)) }
                        messages.send(UiMessage(R.string.browse_request_in_library))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderException.Unauthorized) {
                messages.send(UiMessage(R.string.browse_request_sign_in))
            } catch (e: Exception) {
                messages.send(UiMessage(R.string.browse_request_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                _requesting.update { it - releaseGroupId }
            }
        }
    }
}
