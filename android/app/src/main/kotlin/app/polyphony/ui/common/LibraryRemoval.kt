package app.polyphony.ui.common

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import app.polyphony.R
import app.polyphony.core.data.di.ApplicationScope
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.RemovedAlbum
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

sealed interface RemovalEvent {
    data class Removed(val album: RemovedAlbum) : RemovalEvent
    data class Restored(val title: String) : RemovalEvent
    data class Failed(@StringRes val text: Int, val detail: String) : RemovalEvent
}

/**
 * Removing albums from a continuo server's library (continuo#23), app-wide: the options sheet
 * that starts a removal is gone before it finishes, so the work runs on the application scope and
 * the shell shows the outcome, with Undo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class LibraryRemoval @Inject constructor(
    private val servers: ServerRepository,
    private val requests: RequestRepository,
    private val library: LibraryRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _events = MutableSharedFlow<RemovalEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<RemovalEvent> = _events.asSharedFlow()

    private val recheck = MutableStateFlow(0)

    /** Whether this account may remove albums on the active server (an admin, with it set up). */
    val canRemove: StateFlow<Boolean> = combine(servers.activeServer.map { it?.id }.distinctUntilChanged(), recheck) { id, _ -> id }
        .mapLatest {
            val server = servers.getActive()?.takeIf { requests.supports(it) } ?: return@mapLatest false
            runCatching { requests.account(server).canRemoveAlbums }.getOrDefault(false)
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), false)

    /** Ask the server again (a role can change, or the server can gain the feature). */
    fun recheck() {
        recheck.value++
    }

    fun remove(album: Album) {
        scope.launch {
            val server = servers.getActive()?.takeIf { requests.supports(it) } ?: return@launch
            try {
                val removed = requests.removeAlbum(server, album.id)
                library.forgetAlbum(album.id)
                _events.emit(RemovalEvent.Removed(removed.copy(title = removed.title.ifBlank { album.name })))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.emit(RemovalEvent.Failed(R.string.library_remove_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun restore(removed: RemovedAlbum) {
        scope.launch {
            val server = servers.getActive()?.takeIf { requests.supports(it) } ?: return@launch
            try {
                requests.restoreAlbum(server, removed.id)
                _events.emit(RemovalEvent.Restored(removed.title))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.emit(RemovalEvent.Failed(R.string.library_restore_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }
}

/** [LibraryRemoval] for composables. */
@HiltViewModel
class LibraryRemovalViewModel @Inject constructor(val removal: LibraryRemoval) : ViewModel()
