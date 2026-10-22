package app.polyphony.ui.downloads

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Artist
import app.polyphony.core.domain.model.Genre
import app.polyphony.core.domain.model.Playlist
import app.polyphony.core.domain.model.Feedback
import app.polyphony.core.domain.repository.DownloadRepository
import app.polyphony.core.domain.repository.DownloadRule
import app.polyphony.core.domain.repository.DownloadRuleTarget
import app.polyphony.core.domain.repository.FeedbackRepository
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.core.domain.repository.TranscodeProfileRepository
import app.polyphony.core.domain.transcode.TranscodeProfile
import app.polyphony.navigation.DownloadRuleEditRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

enum class RuleTargetType { FAVORITES, PLAYLIST, ALBUM, ARTIST, GENRE, RECENTLY_ADDED, MOST_PLAYED, RECENTLY_PLAYED;
    val needsEntity: Boolean get() = this == PLAYLIST || this == ALBUM || this == ARTIST || this == GENRE
    val needsCount: Boolean get() = this == RECENTLY_ADDED || this == MOST_PLAYED || this == RECENTLY_PLAYED
}

data class RuleDraft(
    val type: RuleTargetType = RuleTargetType.FAVORITES,
    val entityId: String? = null,
    val entityName: String? = null,
    val count: Int = 20,
    val profileId: String? = null,
    val maxTracks: Int = 0,
    val enabled: Boolean = true,
) {
    val isValid: Boolean get() = !type.needsEntity || entityId != null

    fun toTarget(): DownloadRuleTarget? = when (type) {
        RuleTargetType.FAVORITES -> DownloadRuleTarget.Favorites
        RuleTargetType.PLAYLIST -> entityId?.let { DownloadRuleTarget.Playlist(it, entityName.orEmpty()) }
        RuleTargetType.ALBUM -> entityId?.let { DownloadRuleTarget.Album(it, entityName.orEmpty()) }
        RuleTargetType.ARTIST -> entityId?.let { DownloadRuleTarget.Artist(it, entityName.orEmpty()) }
        RuleTargetType.GENRE -> entityId?.let { DownloadRuleTarget.Genre(it) }
        RuleTargetType.RECENTLY_ADDED -> DownloadRuleTarget.RecentlyAdded(count)
        RuleTargetType.MOST_PLAYED -> DownloadRuleTarget.MostPlayed(count)
        RuleTargetType.RECENTLY_PLAYED -> DownloadRuleTarget.RecentlyPlayed(count)
    }

    companion object {
        fun from(rule: DownloadRule): RuleDraft {
            val base = RuleDraft(profileId = rule.profileId, maxTracks = rule.maxTracks, enabled = rule.enabled)
            return when (val t = rule.target) {
                DownloadRuleTarget.Favorites -> base.copy(type = RuleTargetType.FAVORITES)
                is DownloadRuleTarget.Playlist -> base.copy(type = RuleTargetType.PLAYLIST, entityId = t.playlistId, entityName = t.name)
                is DownloadRuleTarget.Album -> base.copy(type = RuleTargetType.ALBUM, entityId = t.albumId, entityName = t.name)
                is DownloadRuleTarget.Artist -> base.copy(type = RuleTargetType.ARTIST, entityId = t.artistId, entityName = t.name)
                is DownloadRuleTarget.Genre -> base.copy(type = RuleTargetType.GENRE, entityId = t.genre, entityName = t.genre)
                is DownloadRuleTarget.RecentlyAdded -> base.copy(type = RuleTargetType.RECENTLY_ADDED, count = t.albumCount)
                is DownloadRuleTarget.MostPlayed -> base.copy(type = RuleTargetType.MOST_PLAYED, count = t.trackCount)
                is DownloadRuleTarget.RecentlyPlayed -> base.copy(type = RuleTargetType.RECENTLY_PLAYED, count = t.trackCount)
            }
        }
    }
}

/** A pickable library entity for playlist / album / artist / genre rules. */
data class PickableEntity(val id: String, val name: String, val subtitle: String?, val artworkUrl: String?)

sealed interface RuleEditEvent {
    data object Saved : RuleEditEvent
    data object Deleted : RuleEditEvent
    data class Error(val message: String) : RuleEditEvent
}

@HiltViewModel
class DownloadRuleEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val downloads: DownloadRepository,
    private val library: LibraryRepository,
    private val servers: ServerRepository,
    profileRepository: TranscodeProfileRepository,
    feedbackRepository: FeedbackRepository,
) : ViewModel() {

    /** The favourites rule reads as Top rated in Ratings mode. */
    val feedback: StateFlow<Feedback> = feedbackRepository.feedback

    val ruleId: String? = savedStateHandle.toRoute<DownloadRuleEditRoute>().ruleId
    val isNew: Boolean get() = ruleId == null

    private var existing: DownloadRule? = null
    private val _draft = MutableStateFlow(RuleDraft())
    val draft: StateFlow<RuleDraft> = _draft
    private val _loaded = MutableStateFlow(ruleId == null)
    val loaded: StateFlow<Boolean> = _loaded

    private val _events = MutableSharedFlow<RuleEditEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<RuleEditEvent> = _events

    val profiles: StateFlow<List<TranscodeProfile>> = profileRepository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TranscodeProfile.BUILT_INS)

    val playlists: StateFlow<ImmutableList<PickableEntity>> = library.playlists()
        .map { list -> list.map { PickableEntity(it.id, it.name, "${it.trackCount} songs", it.artworkUrl) }.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())
    val albums: StateFlow<ImmutableList<PickableEntity>> = library.albums()
        .map { list -> list.map { PickableEntity(it.id, it.name, it.artistName, it.artworkUrl) }.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())
    val artists: StateFlow<ImmutableList<PickableEntity>> = library.artists()
        .map { list -> list.map { PickableEntity(it.id, it.name, "${it.albumCount} albums", it.artworkUrl) }.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())
    val genres: StateFlow<ImmutableList<PickableEntity>> = library.genres()
        .map { list -> list.map { PickableEntity(it.name, it.name, "${it.trackCount} songs", null) }.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    init {
        if (ruleId != null) {
            viewModelScope.launch {
                val rule = runCatching { downloads.rules.first().firstOrNull { it.id == ruleId } }.getOrNull()
                existing = rule
                if (rule != null) _draft.value = RuleDraft.from(rule)
                _loaded.value = true
            }
        }
    }

    fun setType(type: RuleTargetType) = _draft.update {
        if (it.type == type) it else it.copy(type = type, entityId = null, entityName = null, count = defaultCount(type))
    }
    fun setEntity(entity: PickableEntity) = _draft.update { it.copy(entityId = entity.id, entityName = entity.name) }
    fun setCount(count: Int) = _draft.update { it.copy(count = count.coerceIn(1, 1000)) }
    fun setProfileId(id: String?) = _draft.update { it.copy(profileId = id) }
    fun setMaxTracks(max: Int) = _draft.update { it.copy(maxTracks = max.coerceAtLeast(0)) }
    fun setEnabled(enabled: Boolean) = _draft.update { it.copy(enabled = enabled) }

    private fun defaultCount(type: RuleTargetType) = when (type) {
        RuleTargetType.RECENTLY_ADDED -> 10
        RuleTargetType.MOST_PLAYED, RuleTargetType.RECENTLY_PLAYED -> 50
        else -> 20
    }

    fun save() {
        val d = _draft.value
        val target = d.toTarget()
        if (target == null) {
            _events.tryEmit(RuleEditEvent.Error("needs_target"))
            return
        }
        viewModelScope.launch {
            val serverId = existing?.serverId ?: servers.getActive()?.id
            if (serverId == null) {
                _events.tryEmit(RuleEditEvent.Error("No active server"))
                return@launch
            }
            val now = System.currentTimeMillis()
            val rule = DownloadRule(
                id = existing?.id ?: "rule.${UUID.randomUUID()}",
                serverId = serverId,
                target = target,
                enabled = d.enabled,
                profileId = d.profileId,
                maxTracks = d.maxTracks,
                createdAt = existing?.createdAt ?: now,
                lastEvaluatedAt = existing?.lastEvaluatedAt,
            )
            runCatching { downloads.upsertRule(rule) }
                .onSuccess { _events.tryEmit(RuleEditEvent.Saved) }
                .onFailure { _events.tryEmit(RuleEditEvent.Error(it.message ?: "Couldn't save rule")) }
        }
    }

    fun delete(removeFiles: Boolean) {
        val id = existing?.id ?: ruleId ?: return
        viewModelScope.launch {
            runCatching { downloads.deleteRule(id, removeFiles) }
                .onSuccess { _events.tryEmit(RuleEditEvent.Deleted) }
                .onFailure { _events.tryEmit(RuleEditEvent.Error(it.message ?: "Couldn't delete rule")) }
        }
    }
}

@Suppress("unused")
private fun Playlist.unused() = Unit
@Suppress("unused")
private fun Album.unused() = Unit
@Suppress("unused")
private fun Artist.unused() = Unit
@Suppress("unused")
private fun Genre.unused() = Unit
