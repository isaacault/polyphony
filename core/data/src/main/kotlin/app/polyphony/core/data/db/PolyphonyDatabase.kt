package app.polyphony.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import app.polyphony.core.data.db.dao.AlbumDao
import app.polyphony.core.data.db.dao.ArtistDao
import app.polyphony.core.data.db.dao.GenreDao
import app.polyphony.core.data.db.dao.PlayHistoryDao
import app.polyphony.core.data.db.dao.PlaylistDao
import app.polyphony.core.data.db.dao.RemoteDeviceDao
import app.polyphony.core.data.db.dao.ServerDao
import app.polyphony.core.data.db.dao.TrackDao
import app.polyphony.core.data.db.dao.TranscodeProfileDao
import app.polyphony.core.data.db.entity.AlbumEntity
import app.polyphony.core.data.db.entity.AlbumFtsEntity
import app.polyphony.core.data.db.entity.ArtistEntity
import app.polyphony.core.data.db.entity.ArtistFtsEntity
import app.polyphony.core.data.db.entity.GenreEntity
import app.polyphony.core.data.db.entity.PlayHistoryEntity
import app.polyphony.core.data.db.entity.PlaylistEntity
import app.polyphony.core.data.db.entity.PlaylistTrackEntity
import app.polyphony.core.data.db.entity.RemoteDeviceEntity
import app.polyphony.core.data.db.entity.ServerEntity
import app.polyphony.core.data.db.entity.TrackEntity
import app.polyphony.core.data.db.entity.TrackFtsEntity
import app.polyphony.core.data.db.entity.TranscodeProfileEntity

@Database(
    entities = [
        ServerEntity::class,
        ArtistEntity::class, ArtistFtsEntity::class,
        AlbumEntity::class, AlbumFtsEntity::class,
        TrackEntity::class, TrackFtsEntity::class,
        PlaylistEntity::class, PlaylistTrackEntity::class,
        GenreEntity::class,
        TranscodeProfileEntity::class,
        RemoteDeviceEntity::class,
        PlayHistoryEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class PolyphonyDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun artistDao(): ArtistDao
    abstract fun albumDao(): AlbumDao
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun genreDao(): GenreDao
    abstract fun transcodeProfileDao(): TranscodeProfileDao
    abstract fun remoteDeviceDao(): RemoteDeviceDao
    abstract fun playHistoryDao(): PlayHistoryDao

    /** Remove every cached row belonging to [serverId] (used when a server is removed). */
    suspend fun clearServerData(serverId: String) = withTransaction {
        playlistDao().deleteAllMembers(serverId)
        playlistDao().deleteByServer(serverId)
        trackDao().deleteByServer(serverId)
        albumDao().deleteByServer(serverId)
        artistDao().deleteByServer(serverId)
        genreDao().deleteByServer(serverId)
        playHistoryDao().deleteByServer(serverId)
    }

    companion object {
        const val NAME = "polyphony-library.db"
    }
}
