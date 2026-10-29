package app.polyphony.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import app.polyphony.core.data.db.entity.GenreEntity
import app.polyphony.core.data.db.entity.PlayContextEntity
import app.polyphony.core.data.db.entity.PlayHistoryEntity
import app.polyphony.core.data.db.entity.RemoteDeviceEntity
import app.polyphony.core.data.db.entity.TranscodeProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GenreDao {
    @Query("SELECT * FROM genres WHERE serverId = :serverId ORDER BY name COLLATE NOCASE")
    fun observeAll(serverId: String): Flow<List<GenreEntity>>

    @Upsert
    suspend fun upsertAll(items: List<GenreEntity>)

    @Query("DELETE FROM genres WHERE serverId = :serverId AND syncGeneration < :generation")
    suspend fun deleteStale(serverId: String, generation: Long)

    @Query("DELETE FROM genres WHERE serverId = :serverId")
    suspend fun deleteByServer(serverId: String)
}

@Dao
interface TranscodeProfileDao {
    @Query("SELECT * FROM transcode_profiles ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<TranscodeProfileEntity>>

    @Query("SELECT * FROM transcode_profiles ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<TranscodeProfileEntity>

    @Query("SELECT * FROM transcode_profiles WHERE id = :id")
    suspend fun get(id: String): TranscodeProfileEntity?

    @Upsert
    suspend fun upsert(item: TranscodeProfileEntity)

    @Query("DELETE FROM transcode_profiles WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface RemoteDeviceDao {
    @Query("SELECT * FROM remote_devices ORDER BY COALESCE(lastConnectedAt, 0) DESC, lastSeenAt DESC")
    fun observeAll(): Flow<List<RemoteDeviceEntity>>

    @Query("SELECT * FROM remote_devices WHERE targetKey = :key")
    suspend fun get(key: String): RemoteDeviceEntity?

    @Upsert
    suspend fun upsert(item: RemoteDeviceEntity)

    @Query("UPDATE remote_devices SET profileId = :profileId WHERE targetKey = :key")
    suspend fun setProfile(key: String, profileId: String?)

    @Query("DELETE FROM remote_devices WHERE targetKey = :key")
    suspend fun delete(key: String)
}

@Dao
interface PlayHistoryDao {
    @Insert
    suspend fun insert(item: PlayHistoryEntity)

    @Query("DELETE FROM play_history WHERE serverId = :serverId")
    suspend fun deleteByServer(serverId: String)

    @Query(
        "DELETE FROM play_history WHERE serverId = :serverId AND id NOT IN " +
            "(SELECT id FROM play_history WHERE serverId = :serverId ORDER BY playedAt DESC LIMIT :keep)",
    )
    suspend fun prune(serverId: String, keep: Int)
}

@Dao
interface PlayContextDao {
    @Query("SELECT * FROM play_contexts WHERE serverId = :serverId ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(serverId: String, limit: Int): Flow<List<PlayContextEntity>>

    @Query("SELECT * FROM play_contexts WHERE `key` = :key")
    suspend fun get(key: String): PlayContextEntity?

    @Upsert
    suspend fun upsert(item: PlayContextEntity)

    @Query("DELETE FROM play_contexts WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM play_contexts")
    suspend fun deleteAll()

    @Query("DELETE FROM play_contexts WHERE serverId = :serverId")
    suspend fun deleteByServer(serverId: String)

    @Query(
        "DELETE FROM play_contexts WHERE serverId = :serverId AND `key` NOT IN " +
            "(SELECT `key` FROM play_contexts WHERE serverId = :serverId ORDER BY updatedAt DESC LIMIT :keep)",
    )
    suspend fun prune(serverId: String, keep: Int)
}
