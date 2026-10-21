package app.polyphony.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import app.polyphony.core.domain.model.sortKey

@Entity(
    tableName = "artists",
    primaryKeys = ["serverId", "id"],
    indices = [
        Index("serverId"),
        Index(value = ["serverId", "name"]),
        Index(value = ["serverId", "starred"]),
        Index(value = ["serverId", "syncGeneration"]),
    ],
)
data class ArtistEntity(
    val id: String,
    val serverId: String,
    val name: String,
    val sortName: String?,
    val albumCount: Int,
    val artworkId: String?,
    val artworkUrl: String?,
    val starred: Boolean,
    val rating: Int,
    val musicBrainzId: String?,
    val biography: String?,
    val syncGeneration: Long,
    /** [sortKey] of the name: what the A–Z sorts order by. Derived, so callers never pass it. */
    @ColumnInfo(collate = ColumnInfo.NOCASE, defaultValue = "''")
    val nameSortKey: String = sortKey(name, sortName),
)

@Fts4(contentEntity = ArtistEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "artists_fts")
data class ArtistFtsEntity(
    val name: String,
)

@Entity(
    tableName = "albums",
    primaryKeys = ["serverId", "id"],
    indices = [
        Index("serverId"),
        Index(value = ["serverId", "artistId"]),
        Index(value = ["serverId", "name"]),
        Index(value = ["serverId", "createdAt"]),
        Index(value = ["serverId", "playCount"]),
        Index(value = ["serverId", "lastPlayedAt"]),
        Index(value = ["serverId", "genre"]),
        Index(value = ["serverId", "starred"]),
        Index(value = ["serverId", "syncGeneration"]),
    ],
)
data class AlbumEntity(
    val id: String,
    val serverId: String,
    val name: String,
    val sortName: String?,
    val artistId: String?,
    val artistName: String?,
    val year: Int?,
    val genre: String?,
    val trackCount: Int,
    val durationMs: Long,
    val artworkId: String?,
    val artworkUrl: String?,
    val starred: Boolean,
    val rating: Int,
    val playCount: Long,
    val createdAt: Long,
    val lastPlayedAt: Long?,
    val isCompilation: Boolean,
    val musicBrainzId: String?,
    val syncGeneration: Long,
    /** [sortKey] of the name and of the artist name: what the A–Z sorts order by. Derived. */
    @ColumnInfo(collate = ColumnInfo.NOCASE, defaultValue = "''")
    val nameSortKey: String = sortKey(name, sortName),
    @ColumnInfo(collate = ColumnInfo.NOCASE, defaultValue = "''")
    val artistSortKey: String = sortKey(artistName),
)

@Fts4(contentEntity = AlbumEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "albums_fts")
data class AlbumFtsEntity(
    val name: String,
    val artistName: String?,
)

@Entity(
    tableName = "tracks",
    primaryKeys = ["serverId", "id"],
    indices = [
        Index("serverId"),
        Index(value = ["serverId", "albumId"]),
        Index(value = ["serverId", "artistId"]),
        Index(value = ["serverId", "title"]),
        Index(value = ["serverId", "titleSortKey"]),
        Index(value = ["serverId", "artistSortKey"]),
        Index(value = ["serverId", "albumSortKey"]),
        Index(value = ["serverId", "createdAt"]),
        Index(value = ["serverId", "playCount"]),
        Index(value = ["serverId", "genre"]),
        Index(value = ["serverId", "starred"]),
        Index(value = ["serverId", "syncGeneration"]),
    ],
)
data class TrackEntity(
    val id: String,
    val serverId: String,
    val title: String,
    val albumId: String?,
    val albumName: String?,
    val artistId: String?,
    val artistName: String?,
    val albumArtistName: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val genre: String?,
    val durationMs: Long,
    val bitrateKbps: Int?,
    val sampleRateHz: Int?,
    val bitDepth: Int?,
    val channels: Int?,
    val container: String?,
    val mimeType: String?,
    val sizeBytes: Long?,
    val artworkId: String?,
    val artworkUrl: String?,
    val starred: Boolean,
    val rating: Int,
    val playCount: Long,
    val lastPlayedAt: Long?,
    val replayGainTrackDb: Double?,
    val replayGainAlbumDb: Double?,
    val replayGainTrackPeak: Double?,
    val replayGainAlbumPeak: Double?,
    val musicBrainzId: String?,
    val path: String?,
    val createdAt: Long,
    val syncGeneration: Long,
    /**
     * [sortKey] of the title, artist and album: what the Songs sorts order by. Derived, and
     * indexed so a page of a 50k-track list is an index walk rather than a full sort.
     */
    @ColumnInfo(collate = ColumnInfo.NOCASE, defaultValue = "''")
    val titleSortKey: String = sortKey(title),
    @ColumnInfo(collate = ColumnInfo.NOCASE, defaultValue = "''")
    val artistSortKey: String = sortKey(artistName),
    @ColumnInfo(collate = ColumnInfo.NOCASE, defaultValue = "''")
    val albumSortKey: String = sortKey(albumName),
)

@Fts4(contentEntity = TrackEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "tracks_fts")
data class TrackFtsEntity(
    val title: String,
    val artistName: String?,
    val albumName: String?,
)

@Entity(
    tableName = "playlists",
    primaryKeys = ["serverId", "id"],
    indices = [Index("serverId"), Index(value = ["serverId", "name"])],
)
data class PlaylistEntity(
    val id: String,
    val serverId: String,
    val name: String,
    val description: String?,
    val owner: String?,
    val trackCount: Int,
    val durationMs: Long,
    val artworkId: String?,
    val artworkUrl: String?,
    val isPublic: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val syncGeneration: Long,
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["serverId", "playlistId", "position"],
    indices = [
        Index(value = ["serverId", "playlistId"]),
        Index(value = ["serverId", "trackId"]),
    ],
)
data class PlaylistTrackEntity(
    val serverId: String,
    val playlistId: String,
    val trackId: String,
    val position: Int,
)

@Entity(
    tableName = "genres",
    primaryKeys = ["serverId", "name"],
    indices = [Index("serverId"), Index(value = ["serverId", "syncGeneration"])],
)
data class GenreEntity(
    val name: String,
    val serverId: String,
    val albumCount: Int,
    val trackCount: Int,
    val syncGeneration: Long,
)

@Entity(
    tableName = "play_history",
    indices = [Index(value = ["serverId", "playedAt"]), Index(value = ["serverId", "trackId"]), Index(value = ["serverId", "albumId"])],
)
data class PlayHistoryEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: String,
    val trackId: String,
    val albumId: String?,
    val playedAt: Long,
)

/** Projection used by the sync engine to detect new / changed albums cheaply. */
data class AlbumTrackCount(val id: String, val trackCount: Int)
