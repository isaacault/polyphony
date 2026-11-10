package app.polyphony.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.polyphony.core.data.db.entity.ConnectionSampleEntity

@Dao
interface ConnectionSampleDao {
    @Insert
    suspend fun insert(row: ConnectionSampleEntity): Long

    @Query("SELECT * FROM connection_samples ORDER BY endedAtMs ASC")
    suspend fun all(): List<ConnectionSampleEntity>

    /** The SQL twin of `ConnectionRetention.retain`: drop samples that ended before [cutoffMs]… */
    @Query("DELETE FROM connection_samples WHERE endedAtMs < :cutoffMs")
    suspend fun deleteEndedBefore(cutoffMs: Long)

    /** …and all but the newest [keep]. */
    @Query(
        "DELETE FROM connection_samples WHERE id NOT IN " +
            "(SELECT id FROM connection_samples ORDER BY endedAtMs DESC LIMIT :keep)",
    )
    suspend fun keepNewest(keep: Int)

    @Query("DELETE FROM connection_samples")
    suspend fun deleteAll()
}
