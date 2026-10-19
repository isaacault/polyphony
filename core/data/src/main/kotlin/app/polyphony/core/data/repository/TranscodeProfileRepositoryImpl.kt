package app.polyphony.core.data.repository

import app.polyphony.core.data.db.PolyphonyDatabase
import app.polyphony.core.data.db.entity.RemoteDeviceEntity
import app.polyphony.core.data.mappers.toDomain
import app.polyphony.core.data.mappers.toEntity
import app.polyphony.core.domain.repository.RemoteDevicePreference
import app.polyphony.core.domain.repository.TranscodeProfileRepository
import app.polyphony.core.domain.transcode.OutputTarget
import app.polyphony.core.domain.transcode.TranscodeProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TranscodeProfileRepositoryImpl @Inject constructor(
    db: PolyphonyDatabase,
) : TranscodeProfileRepository {

    private val profileDao = db.transcodeProfileDao()
    private val deviceDao = db.remoteDeviceDao()

    override val profiles: Flow<List<TranscodeProfile>> = profileDao.observeAll()
        .map { custom -> TranscodeProfile.BUILT_INS + custom.map { it.toDomain() } }
        .distinctUntilChanged()

    override suspend fun getProfiles(): List<TranscodeProfile> =
        TranscodeProfile.BUILT_INS + profileDao.getAll().map { it.toDomain() }

    override suspend fun getProfile(id: String): TranscodeProfile? =
        TranscodeProfile.builtIn(id) ?: profileDao.get(id)?.toDomain()

    override suspend fun upsertProfile(profile: TranscodeProfile) {
        require(!profile.builtIn && TranscodeProfile.builtIn(profile.id) == null) {
            "Built-in transcoding profiles cannot be modified"
        }
        require(profile.name.isNotBlank()) { "Profile name must not be blank" }
        profileDao.upsert(profile.toEntity())
    }

    override suspend fun deleteProfile(id: String) {
        if (TranscodeProfile.builtIn(id) != null) return
        profileDao.delete(id)
        // Devices pinned to the deleted profile fall back to the default rules: the resolver treats
        // unknown profile ids as "no override".
    }

    override val remoteDevices: Flow<List<RemoteDevicePreference>> = deviceDao.observeAll()
        .map { list -> list.map { it.toDomain() } }
        .distinctUntilChanged()

    override suspend fun getRemoteDevice(targetKey: String): RemoteDevicePreference? =
        deviceDao.get(targetKey)?.toDomain()

    override suspend fun rememberRemoteDevice(target: OutputTarget, connected: Boolean) {
        if (!target.isRemote) return
        val now = System.currentTimeMillis()
        val existing = deviceDao.get(target.key)
        val model = when (target) {
            is OutputTarget.Cast -> target.modelName
            is OutputTarget.Dlna -> target.modelName
            OutputTarget.Local -> null
        }
        deviceDao.upsert(
            RemoteDeviceEntity(
                targetKey = target.key,
                name = target.displayName.ifBlank { existing?.name ?: target.key },
                modelName = model ?: existing?.modelName,
                profileId = existing?.profileId,
                lastSeenAt = now,
                lastConnectedAt = if (connected) now else existing?.lastConnectedAt,
            ),
        )
    }

    override suspend fun setRemoteDeviceProfile(targetKey: String, profileId: String?) {
        val existing = deviceDao.get(targetKey)
        if (existing == null) {
            deviceDao.upsert(
                RemoteDeviceEntity(
                    targetKey = targetKey,
                    name = targetKey.substringAfter(':'),
                    modelName = null,
                    profileId = profileId,
                    lastSeenAt = System.currentTimeMillis(),
                    lastConnectedAt = null,
                ),
            )
        } else {
            deviceDao.setProfile(targetKey, profileId)
        }
    }

    override suspend fun forgetRemoteDevice(targetKey: String) {
        deviceDao.delete(targetKey)
    }
}
