package app.polyphony.ui.browse.catalog

import app.polyphony.R
import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.CatalogTrack
import app.polyphony.core.domain.model.RequestOutcome
import app.polyphony.core.domain.model.Server
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
 * Album and song requests from a screen that shows catalogue items (#50). [requested] holds what
 * this screen asked for, keyed by MusicBrainz release group (an album, and its songs with it) or
 * by recording (a single song);
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
    fun request(releaseGroupId: String) = send(releaseGroupId) { server -> requests.requestAlbum(server, releaseGroupId) }

    /**
     * Request a song row: just the song when the server fetches single songs and the row knows
     * its recording, else (an older server) its album. Keyed by what was asked for.
     */
    fun requestTrack(track: CatalogTrack) {
        val album = track.albumReleaseGroupId ?: return
        val recording = track.recordingId
        scope.launch {
            val server = servers.getActive()?.takeIf { requests.supports(it) } ?: return@launch
            val songs = recording != null &&
                (songRequests ?: runCatching { requests.account(server).canRequestSongs }.getOrDefault(false).also { songRequests = it })
            if (songs) {
                send(recording!!, R.string.browse_request_song_sent) { requests.requestTrack(it, recording, album) }
            } else {
                request(album)
            }
        }
    }

    // Whether the active server fetches single songs: asked once per screen.
    private var songRequests: Boolean? = null

    private fun send(key: String, sent: Int = R.string.browse_request_sent, call: suspend (Server) -> RequestOutcome) {
        if (key in _requesting.value) return
        _requesting.update { it + key }
        scope.launch {
            try {
                val server = servers.getActive()?.takeIf { requests.supports(it) } ?: return@launch
                when (val outcome = call(server)) {
                    is RequestOutcome.Requested -> {
                        _requested.update { it + (key to Availability.Requested(outcome.requestId, "requested")) }
                        messages.send(UiMessage(sent))
                    }
                    is RequestOutcome.AlreadyRequested -> {
                        _requested.update { it + (key to Availability.Requested(outcome.requestId, "requested")) }
                        messages.send(UiMessage(R.string.browse_request_already))
                    }
                    is RequestOutcome.InLibrary -> {
                        _requested.update { it + (key to Availability.InLibrary(outcome.libraryAlbumId)) }
                        messages.send(UiMessage(R.string.browse_request_in_library))
                    }
                    RequestOutcome.Added -> {
                        _requested.update { it + (key to Availability.InLibrary(null)) }
                        messages.send(UiMessage(R.string.browse_request_added))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderException.Unauthorized) {
                messages.send(UiMessage(R.string.browse_request_sign_in))
            } catch (e: Exception) {
                messages.send(UiMessage(R.string.browse_request_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                _requesting.update { it - key }
            }
        }
    }
}
