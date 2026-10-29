package app.polyphony.core.downloads.engine

import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.repository.DownloadSettings
import app.polyphony.core.domain.repository.DownloadStatus
import app.polyphony.core.domain.repository.DownloadedTrack
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.core.domain.repository.TranscodeProfileRepository
import app.polyphony.core.downloads.db.DownloadDao
import app.polyphony.core.downloads.db.DownloadEntity
import app.polyphony.core.downloads.db.toDomain
import app.polyphony.core.downloads.db.toJson
import app.polyphony.core.downloads.storage.DownloadStorage
import app.polyphony.core.downloads.work.DownloadScheduler
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Low-level download operations shared by the repository and the rule evaluator
 * (keeps the two from depending on each other).
 */
@Singleton
class DownloadEngine @Inject constructor(
    private val dao: DownloadDao,
    private val storage: DownloadStorage,
    private val scheduler: DownloadScheduler,
    private val settingsRepository: SettingsRepository,
    private val profileRepository: TranscodeProfileRepository,
    private val json: Json,
) {

    /** @return number of tracks actually (re)queued. */
    suspend fun enqueue(tracks: List<Track>, profileId: String?, pinned: Boolean, ruleId: String?): Int {
        if (tracks.isEmpty()) return 0
        val settings = settingsRepository.current().downloads
        val wantedProfile = profileId ?: settings.defaultProfileId
        val profiles = profileRepository.getProfiles().associateBy { it.id }
        val wantedRank = RuleDiff.qualityRank(wantedProfile, profiles)
        val existing = tracks.map { it.id }.chunked(CHUNK).flatMap { dao.getByIds(it) }.associateBy { it.trackId }
        val now = System.currentTimeMillis()
        val toUpsert = ArrayList<DownloadEntity>()

        for (track in tracks.distinctBy { it.id }) {
            val have = existing[track.id]
            if (have != null) {
                val haveRank = RuleDiff.qualityRank(have.profileId, profiles)
                val goodEnough = haveRank >= wantedRank
                val busyOrDone = have.status == DownloadStatus.COMPLETED ||
                    have.status == DownloadStatus.QUEUED || have.status == DownloadStatus.DOWNLOADING
                if (goodEnough && busyOrDone) {
                    if (pinned && !have.pinned) dao.setPinned(track.id, true)
                    if (ruleId != null && have.ruleId == null && !pinned) dao.setRule(track.id, ruleId)
                    continue
                }
                if (goodEnough && have.status == DownloadStatus.PAUSED) {
                    if (pinned && !have.pinned) dao.setPinned(track.id, true)
                    continue
                }
                // Upgrade or retry: drop any stale file first.
                storage.delete(have.filePath)
            }
            toUpsert += DownloadEntity(
                trackId = track.id,
                serverId = track.serverId,
                status = DownloadStatus.QUEUED,
                progress = 0f,
                filePath = null,
                mimeType = null,
                sizeBytes = 0L,
                profileId = wantedProfile,
                pinned = pinned || (have?.pinned == true),
                ruleId = ruleId ?: have?.ruleId,
                error = null,
                createdAt = have?.createdAt ?: now,
                lastAccessAt = have?.lastAccessAt ?: now,
                trackJson = track.toJson(json),
            )
        }
        if (toUpsert.isNotEmpty()) {
            dao.upsertAll(toUpsert)
            scheduler.enqueueDrain(settings)
        }
        return toUpsert.size
    }

    suspend fun remove(trackIds: Collection<String>) {
        if (trackIds.isEmpty()) return
        val ids = trackIds.toList()
        ids.chunked(CHUNK).forEach { chunk ->
            dao.getByIds(chunk).forEach { storage.delete(it.filePath) }
            dao.delete(chunk)
        }
    }

    suspend fun removeAllForServer(serverId: String) {
        dao.getAllForServer(serverId).forEach { storage.delete(it.filePath) }
        dao.deleteForServer(serverId)
        if (dao.pendingCount() == 0) scheduler.cancelDrain()
    }

    suspend fun pauseAll() {
        dao.bulkSetStatus(listOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING), DownloadStatus.PAUSED)
        scheduler.cancelDrain()
    }

    suspend fun resumeAll() {
        dao.bulkSetStatus(listOf(DownloadStatus.PAUSED), DownloadStatus.QUEUED)
        if (dao.pendingCount() > 0) scheduler.enqueueDrain(settingsRepository.current().downloads)
    }

    suspend fun retryFailed() {
        dao.bulkSetStatus(listOf(DownloadStatus.FAILED), DownloadStatus.QUEUED)
        if (dao.pendingCount() > 0) scheduler.enqueueDrain(settingsRepository.current().downloads)
    }

    /** Verified local file, or null. Marks the row FAILED if the file vanished. */
    suspend fun localFile(trackId: String): DownloadedTrack? {
        val row = dao.get(trackId) ?: return null
        if (row.status != DownloadStatus.COMPLETED) return null
        if (!storage.exists(row.filePath)) {
            dao.setStatus(trackId, DownloadStatus.FAILED, "File missing from storage")
            return null
        }
        val now = System.currentTimeMillis()
        dao.touch(trackId, now)
        return row.copy(lastAccessAt = now).toDomain()
    }

    suspend fun ensureDrainScheduled(settings: DownloadSettings, replace: Boolean) {
        if (dao.pendingCount() > 0) scheduler.enqueueDrain(settings, replace)
    }

    companion object {
        const val CHUNK = 500
    }
}
