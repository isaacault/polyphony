package app.polyphony.core.data.sync

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.polyphony.core.data.di.ApplicationScope
import app.polyphony.core.data.di.IoDispatcher
import app.polyphony.core.domain.repository.LibrarySyncManager
import app.polyphony.core.domain.repository.ProviderRegistry
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.core.domain.repository.SyncState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibrarySyncManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverRepository: ServerRepository,
    private val providerRegistry: ProviderRegistry,
    private val settingsRepository: SettingsRepository,
    private val sink: RoomSyncSink,
    private val generations: SyncGenerationTracker,
    private val prefs: DataStore<Preferences>,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) : LibrarySyncManager {

    private val engine = LibrarySyncEngine()
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SyncState())
    override val state: StateFlow<SyncState> = _state.asStateFlow()

    init {
        // Keep lastSyncAt in step with the active server.
        scope.launch {
            serverRepository.activeServer.map { it?.id }.distinctUntilChanged().collect { serverId ->
                if (!mutex.isLocked) _state.update { it.copy(lastSyncAt = serverId?.let { id -> lastSyncAt(id) }) }
            }
        }
        // Follow the auto-sync settings.
        scope.launch {
            settingsRepository.settings.map { it.library }.distinctUntilChanged().collect { library ->
                runCatching {
                    if (library.autoSyncHours > 0) scheduleAutoSync(library.autoSyncHours, library.syncOnWifiOnly)
                    else cancelAutoSync()
                }.onFailure { Log.w(TAG, "Could not (re)schedule auto sync", it) }
            }
        }
        // Sync on launch: the manager is created the first time the UI needs it, i.e. at launch.
        scope.launch {
            val settings = settingsRepository.current()
            if (settings.library.syncOnLaunch && serverRepository.getActive() != null) {
                syncNow(full = false)
            }
        }
    }

    override suspend fun syncNow(full: Boolean) {
        if (mutex.isLocked) {
            // A sync is already running: wait for it, don't start another one.
            mutex.withLock { }
            return
        }
        mutex.withLock {
            val server = serverRepository.getActive()
            if (server == null) {
                _state.update { it.copy(status = SyncState.Status.FAILED, lastError = "No server configured") }
                return
            }
            val provider = providerRegistry.forServer(server)
            val lastFull = lastFullSyncAt(server.id)
            val doFull = full || lastFull == null || System.currentTimeMillis() - lastFull > FULL_SYNC_INTERVAL_MS
            val generation = System.currentTimeMillis().coerceAtLeast(generations.lastCompleted(server.id) + 1)
            generations.markRunning(server.id, generation)
            _state.update { it.copy(status = SyncState.Status.RUNNING, progress = null, phase = "Starting", lastError = null) }
            try {
                val summary = withContext(io) {
                    engine.run(provider, sink, generation, doFull) { phase, progress ->
                        _state.update { it.copy(phase = phase, progress = progress) }
                    }
                }
                Log.i(TAG, "Sync finished: $summary")
                val now = System.currentTimeMillis()
                generations.setCompleted(server.id, generation)
                setLastSync(server.id, now, summary.full)
                _state.update { SyncState(status = SyncState.Status.IDLE, lastSyncAt = now) }
            } catch (e: CancellationException) {
                _state.update { it.copy(status = SyncState.Status.IDLE, phase = null, progress = null) }
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Sync failed", e)
                _state.update {
                    it.copy(
                        status = SyncState.Status.FAILED,
                        phase = null,
                        progress = null,
                        lastError = e.message?.takeIf { m -> m.isNotBlank() } ?: e.javaClass.simpleName,
                    )
                }
            } finally {
                generations.clearRunning(server.id)
            }
        }
    }

    override fun scheduleAutoSync(intervalHours: Int, wifiOnly: Boolean) {
        if (intervalHours <= 0) {
            cancelAutoSync()
            return
        }
        val hours = intervalHours.coerceAtLeast(1).toLong()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<LibrarySyncWorker>(hours, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setInitialDelay(hours, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    override fun cancelAutoSync() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    private suspend fun lastSyncAt(serverId: String): Long? = prefs.data.first()[lastSyncKey(serverId)]
    private suspend fun lastFullSyncAt(serverId: String): Long? = prefs.data.first()[lastFullSyncKey(serverId)]

    private suspend fun setLastSync(serverId: String, at: Long, full: Boolean) {
        prefs.edit { p ->
            p[lastSyncKey(serverId)] = at
            if (full) p[lastFullSyncKey(serverId)] = at
        }
    }

    private fun lastSyncKey(serverId: String) = longPreferencesKey("last_sync_$serverId")
    private fun lastFullSyncKey(serverId: String) = longPreferencesKey("last_full_sync_$serverId")

    private companion object {
        const val TAG = "LibrarySync"
        const val WORK_NAME = "library-sync"
        const val FULL_SYNC_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
