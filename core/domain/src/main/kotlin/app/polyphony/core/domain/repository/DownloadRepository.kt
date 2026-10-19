package app.polyphony.core.domain.repository

import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.transcode.TranscodeProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

enum class DownloadStatus { QUEUED, DOWNLOADING, COMPLETED, FAILED, PAUSED }

/** A track stored on device. [pinned] downloads were requested explicitly; others are rule-managed. */
data class DownloadedTrack(
    val trackId: String,
    val serverId: String,
    val status: DownloadStatus,
    /** 0f..1f while downloading. */
    val progress: Float = 0f,
    val filePath: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long = 0L,
    /** Profile the file was fetched with; [TranscodeProfile.ORIGINAL_ID] for the untouched file. */
    val profileId: String = TranscodeProfile.ORIGINAL_ID,
    val pinned: Boolean = true,
    val ruleId: String? = null,
    val error: String? = null,
    val createdAt: Long = 0L,
    val lastAccessAt: Long = 0L,
) {
    val isComplete: Boolean get() = status == DownloadStatus.COMPLETED && filePath != null
}

/** Aggregate state for a collection of tracks (album, playlist, artist). */
data class CollectionDownloadState(
    val total: Int,
    val completed: Int,
    val inProgress: Int,
    val failed: Int,
) {
    val isFullyDownloaded: Boolean get() = total > 0 && completed == total
    val isPartiallyDownloaded: Boolean get() = completed in 1 until total
    val isDownloading: Boolean get() = inProgress > 0
    val progress: Float get() = if (total == 0) 0f else completed.toFloat() / total
}

/** What a download rule keeps on device. */
@Serializable
sealed interface DownloadRuleTarget {
    val label: String

    @Serializable data object Favorites : DownloadRuleTarget { override val label get() = "Favourite songs" }
    @Serializable data class Playlist(val playlistId: String, val name: String) : DownloadRuleTarget { override val label get() = "Playlist · $name" }
    @Serializable data class Album(val albumId: String, val name: String) : DownloadRuleTarget { override val label get() = "Album · $name" }
    @Serializable data class Artist(val artistId: String, val name: String) : DownloadRuleTarget { override val label get() = "Artist · $name" }
    @Serializable data class Genre(val genre: String) : DownloadRuleTarget { override val label get() = "Genre · $genre" }
    @Serializable data class RecentlyAdded(val albumCount: Int) : DownloadRuleTarget { override val label get() = "Last $albumCount albums added" }
    @Serializable data class MostPlayed(val trackCount: Int) : DownloadRuleTarget { override val label get() = "Top $trackCount most played" }
    @Serializable data class RecentlyPlayed(val trackCount: Int) : DownloadRuleTarget { override val label get() = "Last $trackCount played" }
}

/**
 * A standing instruction: keep everything matching [target] downloaded. Rules are re-evaluated after
 * library syncs, after favourite/playlist changes, and on a periodic schedule. When a rule stops
 * matching a track and [DownloadSettings.removeWhenRuleNoLongerMatches] is on, the file is removed
 * unless another rule or a pinned download still wants it.
 */
@Serializable
data class DownloadRule(
    val id: String,
    val serverId: String,
    val target: DownloadRuleTarget,
    val enabled: Boolean = true,
    /** null = [DownloadSettings.defaultProfileId]. */
    val profileId: String? = null,
    /** Optional cap on how many tracks this rule may hold (newest first). 0 = no cap. */
    val maxTracks: Int = 0,
    val createdAt: Long = 0L,
    val lastEvaluatedAt: Long? = null,
)

@Serializable
enum class DownloadLocation { INTERNAL, EXTERNAL }

@Serializable
data class DownloadSettings(
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    /** 0 = unlimited. */
    val maxStorageMb: Long = 0L,
    /** Quality for new downloads unless a rule or request says otherwise. */
    val defaultProfileId: String = TranscodeProfile.ORIGINAL_ID,
    val removeWhenRuleNoLongerMatches: Boolean = true,
    /** Evict least-recently-played rule-managed downloads first when over the storage cap. */
    val evictLeastRecentlyPlayed: Boolean = true,
    val location: DownloadLocation = DownloadLocation.INTERNAL,
    /** Browse and play only what is on device. */
    val offlineMode: Boolean = false,
    /** Always prefer the local file for playback when one exists, even on Wi-Fi. */
    val preferDownloadedForPlayback: Boolean = true,
    val ruleEvaluationHours: Int = 6,
    val concurrentDownloads: Int = 2,
)

data class StorageUsage(
    val usedBytes: Long,
    val limitBytes: Long,
    val deviceFreeBytes: Long,
    val fileCount: Int,
)

interface DownloadRepository {
    /** Downloads for the active server, newest first. */
    val downloads: Flow<List<DownloadedTrack>>
    /** Ids of fully downloaded tracks for the active server. Cheap to observe. */
    fun downloadedTrackIds(): Flow<Set<String>>
    fun downloadState(trackId: String): Flow<DownloadedTrack?>
    fun collectionState(trackIds: List<String>): Flow<CollectionDownloadState>
    val storageUsage: Flow<StorageUsage>

    suspend fun download(tracks: List<Track>, profileId: String? = null, pinned: Boolean = true, ruleId: String? = null)
    suspend fun remove(trackIds: List<String>)
    suspend fun removeAll()
    suspend fun pauseAll()
    suspend fun resumeAll()
    suspend fun retryFailed()
    /** Local file for playback, or null when not fully downloaded. Also bumps lastAccessAt. */
    suspend fun localFile(trackId: String): DownloadedTrack?

    val rules: Flow<List<DownloadRule>>
    suspend fun upsertRule(rule: DownloadRule)
    suspend fun deleteRule(ruleId: String, removeFiles: Boolean)
    suspend fun evaluateRulesNow()
    val ruleEvaluationState: Flow<SyncState>
}
