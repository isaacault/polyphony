package app.polyphony.core.domain.repository

import app.polyphony.core.domain.transcode.NetworkState
import app.polyphony.core.domain.transcode.OutputTarget
import app.polyphony.core.domain.transcode.TranscodeProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** A remote output the app has seen, plus the profile the user pinned to it (if any). */
@Serializable
data class RemoteDevicePreference(
    val targetKey: String,
    val name: String,
    val modelName: String? = null,
    /** null = no override; use cast default / network rules. */
    val profileId: String? = null,
    val lastSeenAt: Long = 0L,
    val lastConnectedAt: Long? = null,
)

interface TranscodeProfileRepository {
    /** Built-in profiles followed by user-defined ones. */
    val profiles: Flow<List<TranscodeProfile>>
    suspend fun getProfiles(): List<TranscodeProfile>
    suspend fun getProfile(id: String): TranscodeProfile?
    suspend fun upsertProfile(profile: TranscodeProfile)
    suspend fun deleteProfile(id: String)

    val remoteDevices: Flow<List<RemoteDevicePreference>>
    suspend fun getRemoteDevice(targetKey: String): RemoteDevicePreference?
    /** Called whenever a remote device is discovered or connected; keeps names fresh. */
    suspend fun rememberRemoteDevice(target: OutputTarget, connected: Boolean)
    suspend fun setRemoteDeviceProfile(targetKey: String, profileId: String?)
    suspend fun forgetRemoteDevice(targetKey: String)
}

interface NetworkMonitor {
    val state: StateFlow<NetworkState>
}
