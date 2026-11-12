package app.polyphony.core.scrobble.db

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A connected account; [secret] is the ListenBrainz token or Last.fm session key. */
@Entity(tableName = "accounts")
data class ScrobbleAccountEntity(
    @PrimaryKey val service: String,
    val username: String,
    val secret: String,
    val nowPlaying: Boolean,
    val needsReconnect: Boolean,
)

/** A counted play waiting for one service; [payload] is a [app.polyphony.core.scrobble.Scrobble] as JSON. */
@Entity(tableName = "queue", indices = [Index("service")])
data class QueuedScrobbleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val service: String,
    val payload: String,
    val attempts: Int = 0,
    val createdAt: Long,
)

/**
 * A counted play the server hasn't been told of yet: [trackId] on [serverId] (a Play now song's
 * id carries its stream), started at [playedAt]. [payload] is the play as this phone's accounts take
 * it ([app.polyphony.core.scrobble.Scrobble] JSON; null when they can't, a song without an artist):
 * once the server has the play, it goes to the accounts the server didn't cover.
 */
@Entity(tableName = "server_plays")
data class ServerPlayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: String,
    val trackId: String,
    val playedAt: Long,
    val payload: String?,
    val attempts: Int = 0,
    val createdAt: Long,
)

data class PendingCount(val service: String, @ColumnInfo(name = "count") val count: Int)

@Dao
interface ScrobbleDao {
    @Query("SELECT * FROM accounts ORDER BY service")
    fun observeAccounts(): Flow<List<ScrobbleAccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY service")
    suspend fun accounts(): List<ScrobbleAccountEntity>

    @Upsert
    suspend fun upsertAccount(account: ScrobbleAccountEntity)

    @Query("DELETE FROM accounts WHERE service = :service")
    suspend fun deleteAccount(service: String)

    @Query("UPDATE accounts SET nowPlaying = :enabled WHERE service = :service")
    suspend fun setNowPlaying(service: String, enabled: Boolean)

    @Query("UPDATE accounts SET needsReconnect = 1 WHERE service = :service")
    suspend fun markNeedsReconnect(service: String)

    @Insert
    suspend fun enqueue(rows: List<QueuedScrobbleEntity>)

    @Query("SELECT * FROM queue WHERE service = :service ORDER BY id LIMIT :limit")
    suspend fun oldest(service: String, limit: Int): List<QueuedScrobbleEntity>

    @Query("DELETE FROM queue WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("UPDATE queue SET attempts = attempts + 1 WHERE id IN (:ids)")
    suspend fun recordFailure(ids: List<Long>)

    @Query("DELETE FROM queue WHERE service = :service")
    suspend fun clearQueue(service: String)

    @Query("SELECT service, COUNT(*) AS count FROM queue GROUP BY service")
    fun observePending(): Flow<List<PendingCount>>

    @Query("SELECT COUNT(*) FROM queue")
    suspend fun pendingCount(): Int

    @Insert
    suspend fun enqueueServerPlay(row: ServerPlayEntity)

    @Query("SELECT * FROM server_plays ORDER BY id LIMIT :limit")
    suspend fun oldestServerPlays(limit: Int): List<ServerPlayEntity>

    @Query("DELETE FROM server_plays WHERE id = :id")
    suspend fun deleteServerPlay(id: Long)

    @Query("UPDATE server_plays SET attempts = attempts + 1 WHERE id = :id")
    suspend fun recordServerPlayFailure(id: Long)

    /** The server has play [id] (or never will): its listen goes on to this phone's accounts, in one step. */
    @Transaction
    suspend fun releaseServerPlay(id: Long, rows: List<QueuedScrobbleEntity>) {
        if (rows.isNotEmpty()) enqueue(rows)
        deleteServerPlay(id)
    }
}

/**
 * Apart from the library database: it holds credentials (left out of the cloud backup, as the
 * library database is for the servers' passwords) and plays not yet sent, which a library rebuild
 * must never lose.
 */
@Database(
    entities = [ScrobbleAccountEntity::class, QueuedScrobbleEntity::class, ServerPlayEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class ScrobbleDatabase : RoomDatabase() {
    abstract fun scrobbleDao(): ScrobbleDao

    companion object {
        const val NAME = "polyphony-scrobble.db"
    }
}
