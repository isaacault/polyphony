package app.polyphony.core.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks the "sync generation" per server: a monotonically increasing stamp written on every
 * cached row. Rows written on demand (detail refreshes) use the running sync's generation when one
 * is active, otherwise the last completed one, so a full sync can safely delete anything older.
 */
@Singleton
class SyncGenerationTracker @Inject constructor(
    private val prefs: DataStore<Preferences>,
) {
    private val running = ConcurrentHashMap<String, Long>()
    private val completedCache = ConcurrentHashMap<String, Long>()

    fun markRunning(serverId: String, generation: Long) {
        running[serverId] = generation
    }

    fun clearRunning(serverId: String) {
        running.remove(serverId)
    }

    suspend fun current(serverId: String): Long = running[serverId] ?: lastCompleted(serverId)

    suspend fun lastCompleted(serverId: String): Long =
        completedCache[serverId] ?: (prefs.data.first()[key(serverId)] ?: 0L).also { completedCache[serverId] = it }

    suspend fun setCompleted(serverId: String, generation: Long) {
        completedCache[serverId] = generation
        prefs.edit { it[key(serverId)] = generation }
    }

    private fun key(serverId: String) = longPreferencesKey("sync_generation_$serverId")
}
