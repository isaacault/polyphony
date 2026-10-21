package app.polyphony.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import app.polyphony.core.data.db.entity.AlbumEntity
import app.polyphony.core.data.db.entity.AlbumTrackCount
import app.polyphony.core.data.db.entity.PlayHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {
    @Upsert
    suspend fun upsertAll(items: List<AlbumEntity>)

    @Upsert
    suspend fun upsert(item: AlbumEntity)

    @RawQuery(observedEntities = [AlbumEntity::class])
    fun observeSorted(query: SupportSQLiteQuery): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE serverId = :serverId AND id = :id")
    fun observe(serverId: String, id: String): Flow<AlbumEntity?>

    @Query("SELECT * FROM albums WHERE serverId = :serverId AND id = :id")
    suspend fun get(serverId: String, id: String): AlbumEntity?

    @Query("SELECT * FROM albums WHERE serverId = :serverId AND artistId = :artistId ORDER BY year DESC, nameSortKey")
    fun observeByArtist(serverId: String, artistId: String): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE serverId = :serverId AND genre = :genre COLLATE NOCASE ORDER BY artistSortKey, year, nameSortKey")
    fun observeByGenre(serverId: String, genre: String): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE serverId = :serverId AND starred = 1 ORDER BY nameSortKey")
    fun observeStarred(serverId: String): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE serverId = :serverId ORDER BY createdAt DESC, name COLLATE NOCASE LIMIT :limit")
    fun observeRecentlyAdded(serverId: String, limit: Int): Flow<List<AlbumEntity>>

    @Query(
        "SELECT a.* FROM albums a LEFT JOIN " +
            "(SELECT albumId, MAX(playedAt) AS lastPlayed FROM play_history WHERE serverId = :serverId GROUP BY albumId) h " +
            "ON h.albumId = a.id " +
            "WHERE a.serverId = :serverId AND (a.lastPlayedAt IS NOT NULL OR h.lastPlayed IS NOT NULL) " +
            "ORDER BY MAX(COALESCE(a.lastPlayedAt, 0), COALESCE(h.lastPlayed, 0)) DESC LIMIT :limit",
    )
    fun observeRecentlyPlayed(serverId: String, limit: Int): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE serverId = :serverId AND playCount > 0 ORDER BY playCount DESC, name COLLATE NOCASE LIMIT :limit")
    fun observeMostPlayed(serverId: String, limit: Int): Flow<List<AlbumEntity>>

    /** Deterministic pseudo-random order for a given seed, so Home stays stable between recompositions. */
    @Query("SELECT * FROM albums WHERE serverId = :serverId ORDER BY ABS((rowid * 1103515245 + :seed) % 2147483647) LIMIT :limit")
    fun observeRandom(serverId: String, limit: Int, seed: Long): Flow<List<AlbumEntity>>

    @Query(
        "SELECT albums.* FROM albums JOIN albums_fts ON albums.rowid = albums_fts.rowid " +
            "WHERE albums_fts MATCH :match AND albums.serverId = :serverId " +
            "ORDER BY albums.name COLLATE NOCASE LIMIT :limit",
    )
    fun searchFts(serverId: String, match: String, limit: Int): Flow<List<AlbumEntity>>

    @Query(
        "SELECT * FROM albums WHERE serverId = :serverId AND (name LIKE :pattern ESCAPE '\\' OR artistName LIKE :pattern ESCAPE '\\') " +
            "ORDER BY name COLLATE NOCASE LIMIT :limit",
    )
    fun searchLike(serverId: String, pattern: String, limit: Int): Flow<List<AlbumEntity>>

    @Query("SELECT id, trackCount FROM albums WHERE serverId = :serverId")
    suspend fun getTrackCounts(serverId: String): List<AlbumTrackCount>

    @Query("UPDATE albums SET starred = :starred WHERE serverId = :serverId AND id = :id")
    suspend fun setStarred(serverId: String, id: String, starred: Boolean)

    @Query("UPDATE albums SET rating = :rating WHERE serverId = :serverId AND id = :id")
    suspend fun setRating(serverId: String, id: String, rating: Int)

    @Query("UPDATE albums SET starred = 0 WHERE serverId = :serverId AND starred = 1")
    suspend fun clearStarred(serverId: String)

    @Query("UPDATE albums SET starred = 1 WHERE serverId = :serverId AND id IN (:ids)")
    suspend fun setStarredIn(serverId: String, ids: List<String>)

    @Query("UPDATE albums SET playCount = playCount + 1, lastPlayedAt = :playedAt WHERE serverId = :serverId AND id = :id")
    suspend fun bumpPlay(serverId: String, id: String, playedAt: Long)

    @Query("DELETE FROM albums WHERE serverId = :serverId AND syncGeneration < :generation")
    suspend fun deleteStale(serverId: String, generation: Long)

    @Query("DELETE FROM albums WHERE serverId = :serverId")
    suspend fun deleteByServer(serverId: String)

    @Query("SELECT COUNT(*) FROM albums WHERE serverId = :serverId")
    suspend fun count(serverId: String): Int
}
