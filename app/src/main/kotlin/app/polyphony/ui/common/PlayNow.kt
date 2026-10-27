package app.polyphony.ui.common

import android.util.Log
import androidx.lifecycle.ViewModel
import app.polyphony.core.data.di.ApplicationScope
import app.polyphony.core.domain.model.CatalogTrack
import app.polyphony.core.domain.model.PlayNowIds
import app.polyphony.core.domain.model.PlayNowStream
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.playback.PlaybackController
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

sealed interface PlayNowEvent {
    val title: String

    data class Finding(override val title: String) : PlayNowEvent
    data class Playing(override val title: String, val streamId: String) : PlayNowEvent
    data class Kept(override val title: String) : PlayNowEvent
    /** Queued to play [next] or at the end; continuo fetches it in turn (polyphony#164). */
    data class Queued(override val title: String, val next: Boolean) : PlayNowEvent
    /** Nobody could send it ([detail] says why); offer to request it instead. */
    data class Failed(override val title: String, val detail: String?, val track: CatalogTrack?) : PlayNowEvent
}

/**
 * Play now (continuo#12), app-wide: tap a song you don't have and continuo fetches the fastest
 * Soulseek copy; it starts playing as soon as the first bytes are in, while it downloads. The
 * outcome shows in the shell (Keep, or Request when nobody has it), whichever screen started it.
 * Runs on the application scope (Dispatchers.Default): calls into the player hop to the main
 * thread, the only one Media3's controller accepts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PlayNow @Inject constructor(
    private val servers: ServerRepository,
    private val requests: RequestRepository,
    private val playback: PlaybackController,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _events = MutableSharedFlow<PlayNowEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<PlayNowEvent> = _events.asSharedFlow()

    /** Whether the active server can play songs you don't have. */
    val available: StateFlow<Boolean> = servers.activeServer.map { it?.id }.distinctUntilChanged()
        .mapLatest {
            val server = servers.getActive()?.takeIf { requests.supports(it) } ?: return@mapLatest false
            runCatching { requests.account(server).canPlayNow }.getOrDefault(false)
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), false)

    // The latest tap wins: finding a second song stops waiting for the first.
    private var finding: Job? = null

    fun play(track: CatalogTrack) {
        val recording = track.recordingId ?: return
        finding?.cancel()
        finding = scope.launch {
            val server = servers.getActive()?.takeIf { requests.supports(it) } ?: return@launch
            _events.emit(PlayNowEvent.Finding(track.title))
            try {
                var stream = requests.startPlayNow(server, recording, track.albumReleaseGroupId, track.title,
                    track.artist.orEmpty(), track.durationMs)
                var waited = 0L
                while (!stream.playable && stream.status != "failed" && waited < FIND_TIMEOUT_MS) {
                    delay(POLL_MS)
                    waited += POLL_MS
                    stream = requests.playNowStatus(server, stream.id)
                }
                if (!stream.playable) {
                    Log.w(TAG, "\"${track.title}\" not playable after ${waited / 1000} s: ${stream.status} ${stream.error.orEmpty()}")
                    _events.emit(PlayNowEvent.Failed(track.title, stream.error ?: "still ${stream.status} after ${waited / 1000} s", track))
                    return@launch
                }
                Log.i(TAG, "\"${track.title}\" playable after ${waited / 1000} s (${stream.status}, ${stream.format})")
                // The player only takes calls on the main thread; this scope runs on Default.
                withContext(Dispatchers.Main) {
                    playback.playTracks(listOf(asTrack(server, stream, track)), 0, queueTitle = track.title)
                }
                _events.emit(PlayNowEvent.Playing(track.title, stream.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Play now of \"${track.title}\" failed", e)
                _events.emit(PlayNowEvent.Failed(track.title, e.message ?: e.javaClass.simpleName, track))
            }
        }
    }

    /**
     * Put a song you don't have in the queue, to play next or at the end (polyphony#164):
     * continuo fetches it in its turn, nearest first, while the queue plays.
     */
    fun queue(track: CatalogTrack, next: Boolean) {
        val recording = track.recordingId ?: return
        scope.launch {
            val server = servers.getActive()?.takeIf { requests.supports(it) } ?: return@launch
            try {
                val stream = requests.startPlayNow(server, recording, track.albumReleaseGroupId, track.title,
                    track.artist.orEmpty(), track.durationMs, queued = true)
                val song = asTrack(server, stream, track)
                withContext(Dispatchers.Main) {
                    if (next) playback.playNext(listOf(song)) else playback.addToQueue(listOf(song))
                }
                _events.emit(PlayNowEvent.Queued(track.title, next))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Queueing \"${track.title}\" failed", e)
                _events.emit(PlayNowEvent.Failed(track.title, e.message ?: e.javaClass.simpleName, track))
            }
        }
    }

    /** Keep it: once the download finishes, continuo files it into your library. */
    fun keep(streamId: String, title: String) {
        scope.launch {
            val server = servers.getActive()?.takeIf { requests.supports(it) } ?: return@launch
            try {
                var waited = 0L
                while (requests.playNowStatus(server, streamId).status != "ready" && waited < KEEP_TIMEOUT_MS) {
                    delay(KEEP_POLL_MS)
                    waited += KEEP_POLL_MS
                }
                requests.keepPlayNow(server, streamId)
                _events.emit(PlayNowEvent.Kept(title))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.emit(PlayNowEvent.Failed(title, e.message, null))
            }
        }
    }

    /** Request the song instead, when nobody could send it right now. */
    fun request(track: CatalogTrack) {
        val recording = track.recordingId ?: return
        val album = track.albumReleaseGroupId ?: return
        scope.launch {
            val server = servers.getActive()?.takeIf { requests.supports(it) } ?: return@launch
            runCatching { requests.requestTrack(server, recording, album) }
        }
    }

    private fun asTrack(server: Server, stream: PlayNowStream, track: CatalogTrack) = Track(
        id = PlayNowIds.of(stream.id),
        serverId = server.id,
        title = stream.title.ifBlank { track.title },
        artistName = stream.artist ?: track.artist,
        albumName = track.albumTitle,
        durationMs = stream.durationMs ?: track.durationMs ?: 0L,
        container = stream.format,
    )

    private companion object {
        const val TAG = "PolyphonyPlayNow"
        const val POLL_MS = 1_000L
        const val FIND_TIMEOUT_MS = 90_000L
        const val KEEP_POLL_MS = 3_000L
        const val KEEP_TIMEOUT_MS = 10 * 60_000L
    }
}

/** [PlayNow] for composables. */
@HiltViewModel
class PlayNowViewModel @Inject constructor(val playNow: PlayNow) : ViewModel()
