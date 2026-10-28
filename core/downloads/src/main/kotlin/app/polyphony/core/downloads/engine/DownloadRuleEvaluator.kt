package app.polyphony.core.downloads.engine

import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.model.TrackSort
import app.polyphony.core.domain.repository.DownloadRule
import app.polyphony.core.domain.repository.DownloadRuleTarget
import app.polyphony.core.domain.repository.DownloadStatus
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.core.domain.repository.SyncState
import app.polyphony.core.domain.repository.TranscodeProfileRepository
import app.polyphony.core.downloads.db.DownloadDao
import app.polyphony.core.downloads.db.DownloadRuleDao
import app.polyphony.core.downloads.db.toDomain
import app.polyphony.core.downloads.work.DownloadNotifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns [DownloadRule]s into concrete enqueue/remove actions using snapshots of the library cache,
 * then enforces the storage cap. Only the active server is evaluated (the library repository is
 * scoped to it); other servers' rules run when they become active.
 */
@Singleton
class DownloadRuleEvaluator @Inject constructor(
    private val ruleDao: DownloadRuleDao,
    private val downloadDao: DownloadDao,
    private val engine: DownloadEngine,
    private val library: LibraryRepository,
    private val serverRepository: ServerRepository,
    private val settingsRepository: SettingsRepository,
    private val profileRepository: TranscodeProfileRepository,
    private val notifications: DownloadNotifications,
    private val json: Json,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    suspend fun evaluate(serverId: String) {
        mutex.withLock {
            // The rules' songs come from the active server's library: evaluated for another server
            // (or after a switch mid-run) they'd mark that server's downloads unwanted and delete them.
            if (!isActive(serverId)) return
            _state.value = _state.value.copy(status = SyncState.Status.RUNNING, progress = null, phase = "Checking download rules", lastError = null)
            try {
                val settings = settingsRepository.current().downloads
                val rules = ruleDao.getForServer(serverId).mapNotNull { it.toDomain(json) }.filter { it.enabled }
                val profiles = profileRepository.getProfiles().associateBy { it.id }

                val current = downloadDao.getAllForServer(serverId).map { it.toDomain() }
                val owning = current.mapNotNullTo(HashSet()) { it.ruleId }
                val perRule = ArrayList<Pair<DownloadRule, List<Track>>>()
                val held = HashSet<String>()
                rules.forEachIndexed { index, rule ->
                    _state.value = _state.value.copy(progress = index.toFloat() / (rules.size + 1), phase = rule.target.label)
                    val tracks = runCatching { tracksFor(rule) }.getOrNull()
                    // A failed lookup, or nothing at all from a rule that owns downloads, is far
                    // likelier a vanished target or a library mid-sync than a wish to delete them.
                    if (tracks == null || (tracks.isEmpty() && rule.id in owning)) held += rule.id
                    perRule += rule to tracks.orEmpty()
                }
                if (!isActive(serverId)) {
                    _state.value = _state.value.copy(status = SyncState.Status.IDLE, progress = null, phase = null)
                    return
                }
                val desired = RuleDiff.mergeDesired(perRule, settings.defaultProfileId, profiles)
                val plan = RuleDiff.compute(desired, current, settings.removeWhenRuleNoLongerMatches, profiles, held)

                _state.value = _state.value.copy(progress = 0.9f, phase = "Applying changes")
                if (plan.remove.isNotEmpty()) engine.remove(plan.remove)
                plan.retag.forEach { (trackId, ruleId) -> downloadDao.setRule(trackId, ruleId) }
                val limitBytes = settings.maxStorageMb * 1024L * 1024L
                val enqueue = if (limitBytes <= 0) {
                    plan.enqueue
                } else {
                    val estimate = RuleDiff.estimateBytes(current)
                    val queued = current.count { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING }
                    RuleDiff.withinBudget(plan.enqueue, limitBytes - downloadDao.usedBytes() - queued * estimate, estimate)
                }
                enqueue.groupBy { it.ruleId to it.profileId }.forEach { (key, items) ->
                    engine.enqueue(items.map { it.track }, profileId = key.second, pinned = false, ruleId = key.first)
                }
                val now = System.currentTimeMillis()
                rules.forEach { ruleDao.setEvaluated(it.id, now) }

                // Storage cap.
                if (limitBytes > 0) {
                    val after = downloadDao.getAllForServer(serverId).map { it.toDomain() }
                    val used = downloadDao.usedBytes()
                    val evict = Eviction.plan(after, used, limitBytes, settings.evictLeastRecentlyPlayed)
                    if (evict.isNotEmpty()) {
                        engine.remove(evict)
                        notifications.evicted(evict.size, settings.maxStorageMb)
                    }
                }
                _state.value = SyncState(status = SyncState.Status.IDLE, lastSyncAt = now)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.value = _state.value.copy(status = SyncState.Status.FAILED, progress = null, phase = null, lastError = e.message ?: e.javaClass.simpleName)
                throw e
            }
        }
    }

    private suspend fun isActive(serverId: String) = serverRepository.getActive()?.id == serverId

    private suspend fun tracksFor(rule: DownloadRule): List<Track> {
        val tracks = when (val t = rule.target) {
            DownloadRuleTarget.Favorites -> library.likedTracks().first()
            is DownloadRuleTarget.Playlist -> library.tracksForPlaylist(t.playlistId).first()
            is DownloadRuleTarget.Album -> library.tracksForAlbum(t.albumId).first()
            is DownloadRuleTarget.Artist -> library.tracksForArtist(t.artistId).first()
            is DownloadRuleTarget.Genre -> library.tracksByGenre(t.genre).first()
            is DownloadRuleTarget.RecentlyAdded -> library.recentlyAddedAlbums(t.albumCount).first()
                .flatMap { album -> library.tracksForAlbum(album.id).first() }
            is DownloadRuleTarget.MostPlayed -> library.tracks(TrackSort.MOST_PLAYED, ascending = false).first().take(t.trackCount)
            is DownloadRuleTarget.RecentlyPlayed -> library.recentlyPlayedTracks(t.trackCount).first()
        }
        return if (rule.maxTracks > 0) tracks.take(rule.maxTracks) else tracks
    }
}
