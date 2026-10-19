package app.polyphony.core.downloads.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.polyphony.core.domain.repository.DownloadStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads WHERE serverId = :serverId ORDER BY createdAt DESC")
    fun observeForServer(serverId: String): Flow<List<DownloadEntity>>

    @Query("SELECT trackId FROM downloads WHERE serverId = :serverId AND status = 'COMPLETED'")
    fun observeCompletedIds(serverId: String): Flow<List<String>>

    @Query("SELECT * FROM downloads WHERE trackId = :trackId")
    fun observe(trackId: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE trackId IN (:ids)")
    fun observeByIds(ids: List<String>): Flow<List<DownloadEntity>>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM downloads WHERE status = 'COMPLETED'")
    fun observeUsedBytes(): Flow<Long>

    @Query("SELECT COUNT(*) FROM downloads WHERE status = 'COMPLETED'")
    fun observeFileCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM downloads WHERE status IN ('QUEUED', 'DOWNLOADING')")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM downloads WHERE trackId = :trackId")
    suspend fun get(trackId: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE trackId IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE serverId = :serverId")
    suspend fun getAllForServer(serverId: String): List<DownloadEntity>

    @Query("SELECT * FROM downloads")
    suspend fun getAll(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE status = 'QUEUED' ORDER BY createdAt ASC")
    suspend fun getQueued(): List<DownloadEntity>

    @Query("SELECT COUNT(*) FROM downloads WHERE status IN ('QUEUED', 'DOWNLOADING')")
    suspend fun pendingCount(): Int

    @Query("SELECT * FROM downloads WHERE ruleId = :ruleId")
    suspend fun getForRule(ruleId: String): List<DownloadEntity>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM downloads WHERE status = 'COMPLETED'")
    suspend fun usedBytes(): Long

    @Upsert
    suspend fun upsert(entity: DownloadEntity)

    @Upsert
    suspend fun upsertAll(entities: List<DownloadEntity>)

    @Query("UPDATE downloads SET status = :status, error = :error WHERE trackId = :trackId")
    suspend fun setStatus(trackId: String, status: DownloadStatus, error: String? = null)

    @Query("UPDATE downloads SET status = :to, error = NULL WHERE status IN (:from)")
    suspend fun bulkSetStatus(from: List<DownloadStatus>, to: DownloadStatus)

    @Query("UPDATE downloads SET status = 'QUEUED' WHERE trackId = :trackId AND status = 'DOWNLOADING'")
    suspend fun requeueIfDownloading(trackId: String)

    @Query("UPDATE downloads SET progress = :progress, status = 'DOWNLOADING' WHERE trackId = :trackId AND status IN ('QUEUED', 'DOWNLOADING')")
    suspend fun setProgress(trackId: String, progress: Float)

    @Query(
        "UPDATE downloads SET status = 'COMPLETED', progress = 1, filePath = :path, sizeBytes = :size, " +
            "mimeType = :mime, error = NULL, lastAccessAt = :at WHERE trackId = :trackId",
    )
    suspend fun markCompleted(trackId: String, path: String, size: Long, mime: String?, at: Long)

    @Query("UPDATE downloads SET lastAccessAt = :at WHERE trackId = :trackId")
    suspend fun touch(trackId: String, at: Long)

    @Query("UPDATE downloads SET pinned = :pinned WHERE trackId = :trackId")
    suspend fun setPinned(trackId: String, pinned: Boolean)

    @Query("UPDATE downloads SET ruleId = :ruleId WHERE trackId = :trackId")
    suspend fun setRule(trackId: String, ruleId: String?)

    @Query("DELETE FROM downloads WHERE trackId IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("DELETE FROM downloads WHERE serverId = :serverId")
    suspend fun deleteForServer(serverId: String)
}

@Dao
interface DownloadRuleDao {

    @Query("SELECT * FROM download_rules WHERE serverId = :serverId ORDER BY createdAt ASC")
    fun observeForServer(serverId: String): Flow<List<DownloadRuleEntity>>

    @Query("SELECT * FROM download_rules")
    fun observeAll(): Flow<List<DownloadRuleEntity>>

    @Query("SELECT * FROM download_rules WHERE serverId = :serverId ORDER BY createdAt ASC")
    suspend fun getForServer(serverId: String): List<DownloadRuleEntity>

    @Query("SELECT * FROM download_rules WHERE id = :id")
    suspend fun get(id: String): DownloadRuleEntity?

    @Upsert
    suspend fun upsert(entity: DownloadRuleEntity)

    @Query("DELETE FROM download_rules WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE download_rules SET lastEvaluatedAt = :at WHERE id = :id")
    suspend fun setEvaluated(id: String, at: Long)
}
