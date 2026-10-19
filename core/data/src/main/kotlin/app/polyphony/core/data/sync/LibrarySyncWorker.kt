package app.polyphony.core.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.polyphony.core.domain.repository.LibrarySyncManager
import app.polyphony.core.domain.repository.SyncState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class LibrarySyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncManager: LibrarySyncManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        syncManager.syncNow(full = inputData.getBoolean(KEY_FULL, false))
        return when (syncManager.state.value.status) {
            SyncState.Status.FAILED -> if (runAttemptCount < 3) Result.retry() else Result.failure()
            else -> Result.success()
        }
    }

    companion object {
        const val KEY_FULL = "full"
    }
}
