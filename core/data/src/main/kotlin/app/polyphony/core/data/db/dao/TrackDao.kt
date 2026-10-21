package app.polyphony.core.data.db.dao

import app.polyphony.core.data.offline.OfflineTrackRef
import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import app.polyphony.core.data.db.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Upsert
    suspend fun upsertAll(items: List<TrackEntity>)

    @Upsert
    suspend fun upsert(item: TrackEntity)

    /** [query] from `SortQueries.tracks` or `SearchQueries`. */
    @RawQuery(observedEntities = [TrackEntity::class])
    fun observeSorted(query: SupportSQLiteQuery): Flow<List<TrackEntity>>

    /** [query] from `SortQueries.trackCount`. */
    @RawQuery(observedEntities = [TrackEntity::class])
    fun observeCountOf(query: SupportSQLiteQuery): Flow<Int>

    /** [query] from `SortQueries.trackIds`. */
    @RawQuery(observedEntities = [TrackEntity::class])
    fun observeIds(query: SupportSQLiteQuery): Flow<List<String>>

    /** [query] from `SortQueries.tracks`; Room's offset paging source, which supports jumping. */
    @RawQuery(observedEntities = [TrackEntity::class])
    fun pagingSorted(query: SupportSQLiteQuery): PagingSource<Int, TrackEntity>

    /** [query] from `SortQueries.tracksSlice`. */
    @RawQuery
    suspend fun listSorted(query: SupportSQLiteQuery): List<TrackEntity>

    /** [query] from `SortQueries.trackSortKeys`: one tiny row per track, in list order. */
    @RawQuery(observedEntities = [TrackEntity::class])
    fun observeSortKeys(query: SupportSQLiteQuery): Flow<List<SortKey>>

    @Query("SELECT COUNT(*) FROM tracks WHERE serverId = :serverId")
    fun observeCount(serverId: String): Flow<Int>

    @Query("SELECT * FROM tracks WHERE serverId = :serverId ORDER BY RANDOM() LIMIT :limit")
    suspend fun random(serverId: String, limit: Int): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE serverId = :serverId AND id = :id")
    fun observe(serverId: String, id: String): Flow<TrackEntity?>

    @Query("SELECT * FROM tracks WHERE serverId = :serverId AND id = :id")
    suspend fun get(serverId: String, id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE serverId = :serverId AND id IN (:ids)")
    suspend fun getByIds(serverId: String, ids: List<String>): List<TrackEntity>

    @Query(
        "SELECT * FROM tracks WHERE serverId = :serverId AND albumId = :albumId " +
            "ORDER BY COALESCE(discNumber, 1), COALESCE(trackNumber, 0), title COLLATE NOCASE",
    )
    fun observeForAlbum(serverId: String, albumId: String): Flow<List<TrackEntity>>

    @Query(
        "SELECT * FROM tracks WHERE serverId = :serverId AND artistId = :artistId " +
            "ORDER BY year DESC, albumSortKey, COALESCE(discNumber, 1), COALESCE(trackNumber, 0)",
    )
    fun observeForArtist(serverId: String, artistId: String): Flow<List<TrackEntity>>

    @Query(
        "SELECT * FROM tracks WHERE serverId = :serverId AND genre = :genre COLLATE NOCASE " +
            "ORDER BY artistSortKey, albumSortKey, COALESCE(discNumber, 1), COALESCE(trackNumber, 0)",
    )
    fun observeByGenre(serverId: String, genre: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE serverId = :serverId AND starred = 1 ORDER BY titleSortKey")
    fun observeStarred(serverId: String): Flow<List<TrackEntity>>

    @Query(
        "SELECT t.* FROM tracks t JOIN " +
            "(SELECT trackId, MAX(playedAt) AS lastPlayed FROM play_history WHERE serverId = :serverId GROUP BY trackId) h " +
            "ON h.trackId = t.id WHERE t.serverId = :serverId ORDER BY h.lastPlayed DESC LIMIT :limit",
    )
    fun observeRecentlyPlayed(serverId: String, limit: Int): Flow<List<TrackEntity>>

    @Query("UPDATE tracks SET starred = :starred WHERE serverId = :serverId AND id = :id")
    suspend fun setStarred(serverId: String, id: String, starred: Boolean)

    @Query("UPDATE tracks SET rating = :rating WHERE serverId = :serverId AND id = :id")
    suspend fun setRating(serverId: String, id: String, rating: Int)

    @Query("UPDATE tracks SET starred = 0 WHERE serverId = :serverId AND starred = 1")
    suspend fun clearStarred(serverId: String)

    @Query("UPDATE tracks SET starred = 1 WHERE serverId = :serverId AND id IN (:ids)")
    suspend fun setStarredIn(serverId: String, ids: List<String>)

    @Query("UPDATE tracks SET playCount = playCount + 1, lastPlayedAt = :playedAt WHERE serverId = :serverId AND id = :id")
    suspend fun bumpPlay(serverId: String, id: String, playedAt: Long)

    @Query("DELETE FROM tracks WHERE serverId = :serverId AND syncGeneration < :generation")
    suspend fun deleteStale(serverId: String, generation: Long)

    @Query("DELETE FROM tracks WHERE serverId = :serverId")
    suspend fun deleteByServer(serverId: String)

    @Query("SELECT COUNT(*) FROM tracks WHERE serverId = :serverId")
    suspend fun count(serverId: String): Int

    /** Lightweight projection used to build the offline-mode index. */
    @Query("SELECT id, albumId, artistId, genre FROM tracks WHERE serverId = :serverId AND id IN (:ids)")
    suspend fun getRefs(serverId: String, ids: List<String>): List<OfflineTrackRef>
}

/** First character of a sort column; see `SortQueries.trackSortKeys`. */
data class SortKey(val k: String?)
