package app.polyphony.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One row per recently played collection: what it is and where the listener left off. */
@Entity(
    tableName = "play_contexts",
    indices = [Index(value = ["serverId", "updatedAt"])],
)
data class PlayContextEntity(
    /** `serverId:KIND:id`, see `PlayContext.key`. */
    @PrimaryKey val key: String,
    val serverId: String,
    val kind: String,
    val contextId: String,
    val title: String,
    val subtitle: String?,
    val artworkUrl: String?,
    val trackId: String?,
    val trackTitle: String?,
    val trackIndex: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val updatedAt: Long,
)
