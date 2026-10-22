package app.polyphony.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.model.Feedback
import app.polyphony.core.domain.repository.DownloadRepository
import app.polyphony.core.domain.repository.DownloadRule
import app.polyphony.core.domain.repository.DownloadStatus
import app.polyphony.core.domain.repository.DownloadedTrack
import app.polyphony.core.domain.repository.FeedbackRepository
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.StorageUsage
import app.polyphony.core.domain.repository.SyncState
import app.polyphony.core.domain.repository.TranscodeProfileRepository
import app.polyphony.core.domain.transcode.TranscodeProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One queued / running / failed download joined with its track metadata. */
data class ActiveDownloadItem(val download: DownloadedTrack, val track: Track?) {
    val id: String get() = download.trackId
}

/** Downloaded tracks grouped by album for the "Downloaded" tab. */
data class DownloadedAlbumGroup(
    val albumId: String?,
    val albumName: String,
    val artistName: String?,
    val artworkUrl: String?,
    val tracks: ImmutableList<Track>,
    val sizeBytes: Long,
) {
    val key: String get() = albumId ?: "album:$albumName"
    val trackIds: List<String> get() = tracks.map { it.id }
}

data class RuleItem(val rule: DownloadRule, val profileName: String?)

data class DownloadsUiState(
    val isLoaded: Boolean = false,
    val active: ImmutableList<ActiveDownloadItem> = persistentListOf(),
    val downloaded: ImmutableList<DownloadedAlbumGroup> = persistentListOf(),
    val downloadedCount: Int = 0,
    val rules: ImmutableList<RuleItem> = persistentListOf(),
    val ruleState: SyncState = SyncState(),
    val storage: StorageUsage? = null,
    val hasPaused: Boolean = false,
    val hasFailed: Boolean = false,
)

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloads: DownloadRepository,
    private val library: LibraryRepository,
    profiles: TranscodeProfileRepository,
    feedbackRepository: FeedbackRepository,
) : ViewModel() {

    /** The favourites rule reads as Top rated in Ratings mode. */
    val feedback: StateFlow<Feedback> = feedbackRepository.feedback

    private val allDownloads: StateFlow<List<DownloadedTrack>> = downloads.downloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Track metadata for everything in the downloads table, refreshed when the id set changes. */
    private val tracksById = allDownloads
        .map { list -> list.map { it.trackId }.toSet() }
        .distinctUntilChanged()
        .mapLatest { ids ->
            if (ids.isEmpty()) {
                emptyMap()
            } else {
                ids.chunked(400).flatMap { chunk -> runCatching { library.getTracks(chunk) }.getOrDefault(emptyList()) }
                    .associateBy { it.id }
            }
        }

    private val profileNames = profiles.profiles.map { list -> list.associate { it.id to it.name } }

    val state: StateFlow<DownloadsUiState> = combine(
        allDownloads,
        tracksById,
        downloads.rules,
        downloads.ruleEvaluationState,
        combine(downloads.storageUsage, profileNames) { s, p -> s to p },
    ) { list, tracks, rules, ruleState, (storage, names) ->
        val active = list
            .filter { it.status != DownloadStatus.COMPLETED }
            .sortedWith(compareBy<DownloadedTrack> { statusOrder(it.status) }.thenByDescending { it.createdAt })
            .map { ActiveDownloadItem(it, tracks[it.trackId]) }
        val completed = list.filter { it.isComplete }
        val groups = completed
            .groupBy { tracks[it.trackId]?.albumId ?: ("album:" + (tracks[it.trackId]?.albumName ?: "Unknown album")) }
            .map { (_, items) ->
                val first = items.firstNotNullOfOrNull { tracks[it.trackId] }
                val groupTracks = items.mapNotNull { tracks[it.trackId] }
                    .sortedWith(compareBy<Track> { it.discNumber ?: 1 }.thenBy { it.trackNumber ?: Int.MAX_VALUE }.thenBy { it.title })
                DownloadedAlbumGroup(
                    albumId = first?.albumId,
                    albumName = first?.albumName ?: "Unknown album",
                    artistName = first?.albumArtistName ?: first?.artistName,
                    artworkUrl = first?.artworkUrl,
                    tracks = groupTracks.toImmutableList(),
                    sizeBytes = items.sumOf { it.sizeBytes },
                )
            }
            .sortedBy { it.albumName.lowercase() }
        DownloadsUiState(
            isLoaded = true,
            active = active.toImmutableList(),
            downloaded = groups.toImmutableList(),
            downloadedCount = completed.size,
            rules = rules.map { RuleItem(it, it.profileId?.let { id -> names[id] ?: TranscodeProfile.builtIn(id)?.name }) }.toImmutableList(),
            ruleState = ruleState,
            storage = storage,
            hasPaused = list.any { it.status == DownloadStatus.PAUSED },
            hasFailed = list.any { it.status == DownloadStatus.FAILED },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

    private fun statusOrder(status: DownloadStatus): Int = when (status) {
        DownloadStatus.DOWNLOADING -> 0
        DownloadStatus.QUEUED -> 1
        DownloadStatus.PAUSED -> 2
        DownloadStatus.FAILED -> 3
        DownloadStatus.COMPLETED -> 4
    }

    fun pauseAll() = viewModelScope.launch { runCatching { downloads.pauseAll() } }
    fun resumeAll() = viewModelScope.launch { runCatching { downloads.resumeAll() } }
    fun retryFailed() = viewModelScope.launch { runCatching { downloads.retryFailed() } }
    fun remove(trackIds: List<String>) = viewModelScope.launch { runCatching { downloads.remove(trackIds) } }
    fun removeAll() = viewModelScope.launch { runCatching { downloads.removeAll() } }
    fun evaluateNow() = viewModelScope.launch { runCatching { downloads.evaluateRulesNow() } }

    fun setRuleEnabled(rule: DownloadRule, enabled: Boolean) {
        viewModelScope.launch { runCatching { downloads.upsertRule(rule.copy(enabled = enabled)) } }
    }

    fun deleteRule(ruleId: String, removeFiles: Boolean) {
        viewModelScope.launch { runCatching { downloads.deleteRule(ruleId, removeFiles) } }
    }
}
