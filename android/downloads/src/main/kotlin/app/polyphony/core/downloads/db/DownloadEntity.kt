package app.polyphony.core.downloads.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.polyphony.core.domain.repository.DownloadStatus

@Entity(
    tableName = "downloads",
    indices = [
        Index("serverId"),
        Index("status"),
        Index("ruleId"),
        Index("lastAccessAt"),
    ],
)
data class DownloadEntity(
    @PrimaryKey val trackId: String,
    val serverId: String,
    val status: DownloadStatus,
    val progress: Float = 0f,
    val filePath: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long = 0L,
    val profileId: String,
    val pinned: Boolean,
    val ruleId: String? = null,
    val error: String? = null,
    val createdAt: Long,
    val lastAccessAt: Long,
    /** Serialized domain `Track`, so downloads work without the library cache. */
    val trackJson: String,
)

@Entity(
    tableName = "download_rules",
    indices = [Index("serverId")],
)
data class DownloadRuleEntity(
    @PrimaryKey val id: String,
    val serverId: String,
    /** Serialized `DownloadRuleTarget` (polymorphic JSON). */
    val targetJson: String,
    val enabled: Boolean,
    val profileId: String? = null,
    val maxTracks: Int = 0,
    val createdAt: Long,
    val lastEvaluatedAt: Long? = null,
)
