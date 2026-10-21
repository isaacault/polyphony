package app.polyphony.core.data.backup

import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.repository.AppSettings
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.core.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One self-contained backup of the user's app settings and configured servers, written to and read
 * back from a single JSON file the user picks through the system file picker (Storage Access
 * Framework).
 *
 * The file includes server credentials (passwords / access tokens) so that a restore is complete and
 * the user does not have to sign in again; it is the user's own private file. [version] lets future
 * readers recognise and migrate older files. The backup [Json] ignores unknown keys and defaults any
 * missing ones, so a file written by a newer build still opens in an older one.
 */
@Serializable
data class BackupFile(
    val version: Int = CURRENT_VERSION,
    val settings: AppSettings = AppSettings(),
    val servers: List<Server> = emptyList(),
) {
    companion object {
        /** Bumped only when the on-disk shape changes in a way a reader must branch on. */
        const val CURRENT_VERSION = 1
    }
}

/** Outcome of a [BackupManager.restore], for the UI to report. */
data class RestoreResult(val serverCount: Int, val version: Int)

/**
 * Builds and consumes [BackupFile] JSON. The pure JSON to model conversion lives here; reading and
 * writing the actual file bytes (through the SAF `ContentResolver`) stays in the UI layer, which
 * owns the picked `Uri`.
 */
@Singleton
class BackupManager @Inject constructor(
    private val json: Json,
    private val settingsRepository: SettingsRepository,
    private val serverRepository: ServerRepository,
) {

    /** A JSON string capturing the current settings and every configured server. */
    suspend fun export(): String = encode(
        BackupFile(
            settings = settingsRepository.current(),
            servers = serverRepository.servers.first(),
        ),
    )

    /**
     * Applies a backup produced by [export]: the settings replace the current ones and each server is
     * added (an existing id is updated in place). Nothing is made active unless there is no active
     * server yet, so restoring over an existing install leaves the user on the server they were using.
     */
    suspend fun restore(text: String): RestoreResult {
        val backup = decode(text)
        settingsRepository.update { backup.settings }
        for (server in backup.servers) {
            serverRepository.add(server, makeActive = false)
        }
        return RestoreResult(serverCount = backup.servers.size, version = backup.version)
    }

    fun encode(backup: BackupFile): String = json.encodeToString(BackupFile.serializer(), backup)

    fun decode(text: String): BackupFile = json.decodeFromString(BackupFile.serializer(), text)
}
