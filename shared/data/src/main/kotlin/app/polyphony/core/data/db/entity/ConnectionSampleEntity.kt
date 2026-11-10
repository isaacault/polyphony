package app.polyphony.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One `ConnectionSample`: a window of streaming and what the connection was like in it. */
@Entity(
    tableName = "connection_samples",
    indices = [Index(value = ["endedAtMs"])],
)
data class ConnectionSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtMs: Long,
    val endedAtMs: Long,
    /** `ConnectionSample.Trigger` (TRIGGER is an SQL keyword, hence the name). */
    val endedBy: String,
    val network: String,
    val metered: Boolean,
    val vpn: Boolean,
    val atHome: Boolean?,
    val responseMs: Int?,
    val networkBytes: Long,
    val networkMs: Long,
    val throughputKbps: Int?,
    val cachedBytes: Long,
    val downloadedBytes: Long,
    val stalls: Int,
    val stallMs: Long,
    val serverId: String?,
    val profileId: String?,
    val codec: String?,
    val maxBitrateKbps: Int?,
    val reason: String?,
    val capped: Boolean,
    val cappedMs: Long,
)
