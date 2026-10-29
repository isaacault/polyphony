package app.polyphony.core.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey val id: String,
    val type: String,
    val name: String,
    val baseUrl: String,
    val username: String,
    val credential: String,
    val userId: String?,
    val deviceId: String,
    val optionsJson: String,
    val createdAt: Long,
)
