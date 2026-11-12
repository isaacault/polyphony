package app.polyphony.ui.browse.smart

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.polyphony.core.domain.model.SmartField
import app.polyphony.core.domain.model.SmartOp
import app.polyphony.core.domain.model.SmartPlaylist
import app.polyphony.core.domain.model.SmartRule
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.model.TrackSort
import app.polyphony.core.domain.playback.PlaybackController
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.navigation.SmartPlaylistEditRoute
import app.polyphony.navigation.SmartPlaylistRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** The smart playlists, kept with the library settings (#33). */
private suspend fun SettingsRepository.updateSmartPlaylists(transform: (List<SmartPlaylist>) -> List<SmartPlaylist>) =
    update { it.copy(library = it.library.copy(smartPlaylists = transform(it.library.smartPlaylists))) }

/** One smart playlist: its songs, live, to play. */
@HiltViewModel
class SmartPlaylistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    library: LibraryRepository,
    private val playback: PlaybackController,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val id = savedStateHandle.toRoute<SmartPlaylistRoute>().id

    val playlist: StateFlow<SmartPlaylist?> = settings.settings
        .map { s -> s.library.smartPlaylists.firstOrNull { it.id == id } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val tracks: StateFlow<ImmutableList<Track>?> = playlist.filterNotNull()
        .flatMapLatest { library.smartPlaylistTracks(it) }
        .map { it.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val playbackState = playback.state

    fun playTrack(index: Int) {
        val list = tracks.value ?: return
        playback.playTracks(list, index, queueTitle = playlist.value?.name)
    }

    fun playAll(shuffle: Boolean) {
        val list = tracks.value ?: return
        if (list.isNotEmpty()) playback.playTracks(list, 0, shuffle, playlist.value?.name)
    }

    fun delete() {
        viewModelScope.launch { settings.updateSmartPlaylists { list -> list.filterNot { it.id == id } } }
    }
}

/** A smart playlist being made or changed, with how many songs its rules find as they change. */
@HiltViewModel
class SmartPlaylistEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    library: LibraryRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val existingId: String? = savedStateHandle.toRoute<SmartPlaylistEditRoute>().id
    val isNew: Boolean get() = existingId == null

    private val _draft = MutableStateFlow(
        SmartPlaylist(
            id = existingId ?: UUID.randomUUID().toString(),
            name = "",
            rules = listOf(SmartRule(SmartField.GENRE, SmartField.GENRE.ops.first())),
        ),
    )
    val draft: StateFlow<SmartPlaylist> = _draft.asStateFlow()

    /** Songs the rules find now (null while counting), a moment after each change. */
    val matches: StateFlow<Int?> = _draft
        .mapLatest { delay(COUNT_AFTER_MS); it }
        .flatMapLatest { library.smartPlaylistTracks(it) }
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        if (existingId != null) {
            viewModelScope.launch {
                settings.settings.first().library.smartPlaylists.firstOrNull { it.id == existingId }?.let { _draft.value = it }
            }
        }
    }

    fun setName(name: String) = _draft.update { it.copy(name = name) }
    fun setMatchAll(all: Boolean) = _draft.update { it.copy(matchAll = all) }
    fun setSort(sort: TrackSort) = _draft.update { it.copy(sort = sort) }
    fun setAscending(ascending: Boolean) = _draft.update { it.copy(ascending = ascending) }
    fun setLimit(text: String) = _draft.update { it.copy(limit = text.filter(Char::isDigit).take(5).toIntOrNull() ?: 0) }

    fun addRule() = _draft.update { it.copy(rules = it.rules + SmartRule(SmartField.GENRE, SmartField.GENRE.ops.first())) }
    fun removeRule(index: Int) = _draft.update { it.copy(rules = it.rules.filterIndexed { i, _ -> i != index }) }

    /** A new field keeps the value only when it's the same kind, and takes that field's usual operator. */
    fun setField(index: Int, field: SmartField) = updateRule(index) { rule ->
        if (rule.field == field) rule
        else SmartRule(field, field.ops.first(), if (rule.field.kind == field.kind) rule.value else "")
    }

    fun setOp(index: Int, op: SmartOp) = updateRule(index) { it.copy(op = op) }
    fun setValue(index: Int, value: String) = updateRule(index) { it.copy(value = value) }

    private fun updateRule(index: Int, change: (SmartRule) -> SmartRule) =
        _draft.update { p -> p.copy(rules = p.rules.mapIndexed { i, r -> if (i == index) change(r) else r }) }

    /** Saves it (unfinished rules dropped, a name given if none); [onSaved] gets its id. */
    fun save(defaultName: String, onSaved: (String) -> Unit) {
        val playlist = _draft.value.let { it.copy(name = it.name.trim().ifBlank { defaultName }, rules = it.activeRules) }
        viewModelScope.launch {
            settings.updateSmartPlaylists { list ->
                if (list.any { it.id == playlist.id }) list.map { if (it.id == playlist.id) playlist else it } else list + playlist
            }
            onSaved(playlist.id)
        }
    }

    private companion object {
        const val COUNT_AFTER_MS = 300L
    }
}
