package app.polyphony.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.polyphony.core.domain.model.CatalogTrack
import app.polyphony.core.domain.model.RemovedAlbum
import app.polyphony.core.domain.playback.CastController
import app.polyphony.core.domain.playback.CastState
import app.polyphony.core.domain.playback.PlaybackController
import app.polyphony.core.domain.playback.PlaybackState
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.ListeningHistoryRepository
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.inbox.InboxBadges
import app.polyphony.navigation.DeepLink
import app.polyphony.ui.common.LibraryRemoval
import app.polyphony.ui.common.PlayNow
import app.polyphony.ui.common.PlayNowEvent
import app.polyphony.ui.common.RemovalEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ShellViewModel @Inject constructor(
    private val playbackController: PlaybackController,
    castController: CastController,
    private val serverRepository: ServerRepository,
    settingsRepository: SettingsRepository,
    private val libraryRepository: LibraryRepository,
    private val requestRepository: RequestRepository,
    private val inboxBadges: InboxBadges,
    private val libraryRemoval: LibraryRemoval,
    private val playNow: PlayNow,
    private val history: ListeningHistoryRepository,
) : ViewModel() {

    /** Play now outcomes (continuo#12), wherever the song was tapped. */
    val playNowEvents: SharedFlow<PlayNowEvent> = playNow.events

    fun keepPlayNow(streamId: String, title: String) = playNow.keep(streamId, title)

    fun requestInstead(track: CatalogTrack) = playNow.request(track)

    /** Removals and restores finish here, whichever screen started them (continuo#23). */
    val removalEvents: SharedFlow<RemovalEvent> = libraryRemoval.events

    fun undoRemoval(album: RemovedAlbum) = libraryRemoval.restore(album)

    val playback: StateFlow<PlaybackState> = playbackController.state
    val cast: StateFlow<CastState> = castController.state

    /** null until the server list has loaded, so the shell never flashes onboarding at start. */
    val hasServers: StateFlow<Boolean?> = serverRepository.servers
        .map { it.isNotEmpty() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val onboardingComplete: StateFlow<Boolean> = settingsRepository.settings
        .map { it.onboardingComplete }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Any continuo-linked server: request notifications (#118) are worth asking permission for. */
    val continuoLinked: StateFlow<Boolean> = serverRepository.servers
        .map { list -> list.any { requestRepository.supports(it) } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val hapticsEnabled: StateFlow<Boolean> = settingsRepository.settings
        .map { it.playback.hapticFeedback }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /**
     * Shuffle the whole library and start playback. Mirrors `LibraryViewModel.shuffleLibrary` so
     * the `polyphony://shuffle` launcher shortcut behaves exactly like "Shuffle all" in Library:
     * a server-side random pick when online, a cached random sample otherwise.
     */
    /** The most recent collection where it was left (the "Resume" launcher shortcut). */
    fun resumeLatest() {
        viewModelScope.launch {
            val latest = runCatching { history.recent(1).first().firstOrNull() }.getOrNull() ?: return@launch
            playbackController.resumeContext(latest.context)
        }
    }

    fun shuffleLibrary() {
        viewModelScope.launch {
            val remote = runCatching { libraryRepository.getRandomTracks(SHUFFLE_CAP) }.getOrDefault(emptyList())
            val picks = remote.ifEmpty { libraryRepository.randomCachedTracks(SHUFFLE_CAP) }
            if (picks.isNotEmpty()) {
                playbackController.playTracks(picks, 0, shuffle = false, queueTitle = "Shuffle all")
            }
        }
    }

    /**
     * Makes the server an album link points at the active one (switching if needed), so the album
     * screen loads it from there. False when no configured server matches.
     */
    suspend fun activateServerFor(link: DeepLink.Album): Boolean {
        val server = DeepLink.serverFor(link, serverRepository.servers.first()) ?: return false
        if (serverRepository.getActive()?.id != server.id) serverRepository.setActive(server.id)
        return true
    }

    /**
     * Makes the server a notification came from active, when it's still configured, so a request
     * or inbox link opens on the right server.
     */
    suspend fun activateServer(serverId: String) {
        if (serverRepository.get(serverId) == null) return
        if (serverRepository.getActive()?.id != serverId) serverRepository.setActive(serverId)
    }

    /** A Polyphony-posted notification was opened: mark it read on continuo (best effort). */
    fun markNotificationRead(receipt: DeepLink.Receipt) {
        viewModelScope.launch {
            val server = serverRepository.get(receipt.serverId)?.takeIf { requestRepository.supports(it) } ?: return@launch
            try {
                inboxBadges.set(server.id, requestRepository.markRead(server, listOf(receipt.notificationId)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline or signed out: it stays unread in the inbox.
            }
        }
    }

    private companion object {
        const val SHUFFLE_CAP = 300
    }
}
