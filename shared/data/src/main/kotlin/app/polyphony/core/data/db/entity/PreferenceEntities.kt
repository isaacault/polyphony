package app.polyphony.core.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transcode_profiles")
data class TranscodeProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val codec: String,
    val maxBitrateKbps: Int,
    val maxSampleRateHz: Int,
    val maxBitDepth: Int,
)

@Entity(tableName = "remote_devices")
data class RemoteDeviceEntity(
    @PrimaryKey val targetKey: String,
    val name: String,
    val modelName: String?,
    val profileId: String?,
    val lastSeenAt: Long,
    val lastConnectedAt: Long?,
)
