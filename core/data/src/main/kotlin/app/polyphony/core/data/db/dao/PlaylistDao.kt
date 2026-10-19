package app.polyphony.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.polyphony.core.data.db.entity.PlaylistEntity
import app.polyphony.core.data.db.entity.PlaylistTrackEntity
import app.polyphony.core.data.db.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists WHERE serverId = :serverId ORDER BY name COLLATE NOCASE")
    fun observeAll(serverId: String): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE serverId = :serverId AND id = :id")
    fun observe(serverId: String, id: String): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE serverId = :serverId AND id = :id")
    suspend fun get(serverId: String, id: String): PlaylistEntity?

    @Upsert
    suspend fun upsertAll(items: List<PlaylistEntity>)

    @Upsert
    suspend fun upsert(item: PlaylistEntity)

    @Query("UPDATE playlists SET name = :name WHERE serverId = :serverId AND id = :id")
    suspend fun rename(serverId: String, id: String, name: String)

    @Query("UPDATE playlists SET trackCount = :trackCount WHERE serverId = :serverId AND id = :id")
    suspend fun setTrackCount(serverId: String, id: String, trackCount: Int)

    @Query("DELETE FROM playlists WHERE serverId = :serverId AND id = :id")
    suspend fun delete(serverId: String, id: String)

    @Query("DELETE FROM playlists WHERE serverId = :serverId AND id NOT IN (:ids)")
    suspend fun deleteExcept(serverId: String, ids: List<String>)

    @Query("DELETE FROM playlists WHERE serverId = :serverId")
    suspend fun deleteByServer(serverId: String)

    // ---- membership ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(items: List<PlaylistTrackEntity>)

    @Query("SELECT * FROM playlist_tracks WHERE serverId = :serverId AND playlistId = :playlistId ORDER BY position")
    suspend fun getMembers(serverId: String, playlistId: String): List<PlaylistTrackEntity>

    @Query("DELETE FROM playlist_tracks WHERE serverId = :serverId AND playlistId = :playlistId")
    suspend fun deleteMembers(serverId: String, playlistId: String)

    @Query("DELETE FROM playlist_tracks WHERE serverId = :serverId AND playlistId NOT IN (:ids)")
    suspend fun deleteMembersExcept(serverId: String, ids: List<String>)

    @Query("DELETE FROM playlist_tracks WHERE serverId = :serverId")
    suspend fun deleteAllMembers(serverId: String)

    @Query(
        "SELECT tracks.* FROM playlist_tracks JOIN tracks " +
            "ON tracks.serverId = playlist_tracks.serverId AND tracks.id = playlist_tracks.trackId " +
            "WHERE playlist_tracks.serverId = :serverId AND playlist_tracks.playlistId = :playlistId " +
            "ORDER BY playlist_tracks.position",
    )
    fun observeTracks(serverId: String, playlistId: String): Flow<List<TrackEntity>>

    @Query("SELECT DISTINCT playlistId FROM playlist_tracks WHERE serverId = :serverId AND trackId IN (:ids)")
    suspend fun getPlaylistIdsForTracks(serverId: String, ids: List<String>): List<String>

    /** Re-emits whenever playlist membership changes; used to refresh the offline-mode index. */
    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE serverId = :serverId")
    fun observeMembershipCount(serverId: String): Flow<Int>

    @Transaction
    suspend fun replaceMembers(serverId: String, playlistId: String, members: List<PlaylistTrackEntity>) {
        deleteMembers(serverId, playlistId)
        if (members.isNotEmpty()) insertMembers(members)
    }
}
