package app.polyphony.core.data.backup

import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerOptions
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
 * the user does not have to sign in again; it is the user's own private file. The one exception is a
 * continuo server ([Server.isContinuo]): its credential is this install's device session, which must
 * never be shared with a second phone, so it is left out ([Server.withoutContinuoSession]) and a
 * restored copy asks for the continuo password once. [version] lets future
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

/**
 * Outcome of a [BackupManager.restore], for the UI to report. [needsSignIn] counts the restored
 * continuo servers that need the continuo password again ([Server.needsContinuoSignIn]).
 */
data class RestoreResult(val serverCount: Int, val version: Int, val needsSignIn: Int = 0)

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
            servers = serverRepository.servers.first().map { it.withoutContinuoSession() },
        ),
    )

    /**
     * Applies a backup produced by [export]: the settings replace the current ones and each server is
     * added (an existing id is updated in place). Nothing is made active unless there is no active
     * server yet, so restoring over an existing install leaves the user on the server they were using.
     *
     * A continuo server never takes the file's device session: older backups still carry it, and a
     * phone that shares a session with the one it was copied from gets both revoked at the first
     * token refresh. It keeps this install's session when the same server is already here, and
     * otherwise is added without one, to be signed in again from Server settings.
     */
    suspend fun restore(text: String): RestoreResult {
        val backup = decode(text)
        settingsRepository.update { backup.settings }
        val restored = backup.servers.map { restoredServer(it) }
        for (server in restored) {
            serverRepository.add(server, makeActive = false)
        }
        return RestoreResult(
            serverCount = restored.size,
            version = backup.version,
            needsSignIn = restored.count { it.needsContinuoSignIn },
        )
    }

    private suspend fun restoredServer(fromBackup: Server): Server {
        if (!fromBackup.isContinuo) return fromBackup
        val stripped = fromBackup.withoutContinuoSession()
        // Same server already on this install: its session is this phone's own (and its refresh
        // token may be newer than any copy), so it carries over the backup's other settings.
        val local = serverRepository.get(fromBackup.id)?.takeIf { it.isContinuo } ?: return stripped
        return stripped.copy(
            credential = local.credential,
            options = stripped.options + local.options.filterKeys { it in ServerOptions.CONTINUO_KEYS },
        )
    }

    fun encode(backup: BackupFile): String = json.encodeToString(BackupFile.serializer(), backup)

    fun decode(text: String): BackupFile = json.decodeFromString(BackupFile.serializer(), text)
}
