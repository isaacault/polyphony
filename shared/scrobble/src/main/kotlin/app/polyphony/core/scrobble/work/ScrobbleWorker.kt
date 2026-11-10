package app.polyphony.core.scrobble.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.polyphony.core.scrobble.queue.ScrobbleDrainer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Sends the queued plays ([ScrobbleDrainer]); asks WorkManager to try again later when a service couldn't take them. */
@HiltWorker
class ScrobbleWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val drainer: ScrobbleDrainer,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (drainer.drain()) {
        ScrobbleDrainer.Outcome.DONE -> Result.success()
        ScrobbleDrainer.Outcome.RETRY -> Result.retry()
    }
}

/**
 * One unique drain at a time, run when there's a network; WorkManager keeps it through process death
 * and reboots, with exponential backoff from 30 seconds (capped by WorkManager at five hours).
 */
@Singleton
class ScrobbleScheduler @Inject constructor(@ApplicationContext private val context: Context) {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    suspend fun drain() {
        // A running drain gets a follow-up (it may have read the queue before the new play); one
        // waiting for the network or its backoff will pick the new play up anyway.
        val infos = runCatching { workManager.getWorkInfosForUniqueWorkFlow(WORK).first() }.getOrDefault(emptyList())
        val policy = when {
            infos.any { it.state == WorkInfo.State.RUNNING } -> ExistingWorkPolicy.APPEND_OR_REPLACE
            infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } -> ExistingWorkPolicy.KEEP
            else -> ExistingWorkPolicy.REPLACE
        }
        workManager.enqueueUniqueWork(WORK, policy, request())
    }

    /** Right away, whatever backoff is pending (an account was just connected again). */
    fun drainNow() {
        workManager.enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request())
    }

    private fun request() = OneTimeWorkRequestBuilder<ScrobbleWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()

    companion object {
        const val WORK = "polyphony-scrobble"
    }
}
