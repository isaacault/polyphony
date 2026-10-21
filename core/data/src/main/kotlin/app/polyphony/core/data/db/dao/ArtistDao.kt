package app.polyphony.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import app.polyphony.core.data.db.entity.AlbumEntity
import app.polyphony.core.data.db.entity.ArtistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtistDao {
    @Upsert
    suspend fun upsertAll(items: List<ArtistEntity>)

    @Upsert
    suspend fun upsert(item: ArtistEntity)

    @RawQuery(observedEntities = [ArtistEntity::class, AlbumEntity::class])
    /** [query] from `SortQueries` or `SearchQueries`. */
    fun observeSorted(query: SupportSQLiteQuery): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artists WHERE serverId = :serverId AND id = :id")
    fun observe(serverId: String, id: String): Flow<ArtistEntity?>

    @Query("SELECT * FROM artists WHERE serverId = :serverId AND id = :id")
    suspend fun get(serverId: String, id: String): ArtistEntity?

    @Query("SELECT * FROM artists WHERE serverId = :serverId AND starred = 1 ORDER BY nameSortKey")
    fun observeStarred(serverId: String): Flow<List<ArtistEntity>>

    @Query("UPDATE artists SET starred = :starred WHERE serverId = :serverId AND id = :id")
    suspend fun setStarred(serverId: String, id: String, starred: Boolean)

    @Query("UPDATE artists SET rating = :rating WHERE serverId = :serverId AND id = :id")
    suspend fun setRating(serverId: String, id: String, rating: Int)

    @Query("UPDATE artists SET starred = 0 WHERE serverId = :serverId AND starred = 1")
    suspend fun clearStarred(serverId: String)

    @Query("UPDATE artists SET starred = 1 WHERE serverId = :serverId AND id IN (:ids)")
    suspend fun setStarredIn(serverId: String, ids: List<String>)

    @Query("DELETE FROM artists WHERE serverId = :serverId AND syncGeneration < :generation")
    suspend fun deleteStale(serverId: String, generation: Long)

    @Query("DELETE FROM artists WHERE serverId = :serverId")
    suspend fun deleteByServer(serverId: String)

    @Query("SELECT COUNT(*) FROM artists WHERE serverId = :serverId")
    suspend fun count(serverId: String): Int
}
