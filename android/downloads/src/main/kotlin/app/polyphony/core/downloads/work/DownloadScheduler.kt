package app.polyphony.core.downloads.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.polyphony.core.domain.repository.DownloadSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Enqueues the queue-drainer and the periodic rule evaluation with constraints from settings. */
@Singleton
class DownloadScheduler @Inject constructor(@ApplicationContext private val context: Context) {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    fun constraintsFor(settings: DownloadSettings): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(settings.chargingOnly)
        .setRequiresStorageNotLow(true)
        .build()

    /**
     * Make sure a drainer will run. If one is already running we append a follow-up run so items
     * enqueued during its final moments are never stranded; if one is merely waiting for its
     * constraints we leave it alone. [replace] forces fresh constraints (settings changed).
     */
    suspend fun enqueueDrain(settings: DownloadSettings, replace: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(constraintsFor(settings))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        val policy = if (replace) {
            ExistingWorkPolicy.REPLACE
        } else {
            val infos = runCatching { workManager.getWorkInfosForUniqueWorkFlow(DRAIN_WORK).first() }.getOrDefault(emptyList())
            when {
                infos.any { it.state == WorkInfo.State.RUNNING } -> ExistingWorkPolicy.APPEND_OR_REPLACE
                infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } -> ExistingWorkPolicy.KEEP
                else -> ExistingWorkPolicy.REPLACE
            }
        }
        workManager.enqueueUniqueWork(DRAIN_WORK, policy, request)
    }

    fun cancelDrain() {
        workManager.cancelUniqueWork(DRAIN_WORK)
    }

    fun schedulePeriodicRuleEvaluation(hours: Int) {
        if (hours <= 0) {
            cancelPeriodicRuleEvaluation()
            return
        }
        val request = PeriodicWorkRequestBuilder<RuleEvaluationWorker>(hours.toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(RULES_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancelPeriodicRuleEvaluation() {
        workManager.cancelUniqueWork(RULES_WORK)
    }

    companion object {
        const val DRAIN_WORK = "polyphony-downloads"
        const val RULES_WORK = "polyphony-download-rules"
        const val TAG = "polyphony-downloads"
    }
}
