package app.polyphony.inbox

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.ServerRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the inbox poll (#118) scheduled exactly while a continuo-linked server is configured:
 * every 15 minutes (WorkManager's minimum) on any connected network, plus once on app open.
 * Plain Subsonic and Jellyfin setups never get a job.
 */
@Singleton
class InboxScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val servers: ServerRepository,
    private val requests: RequestRepository,
) {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Follow the server list for the life of [scope] (the application's). */
    fun start(scope: CoroutineScope) {
        scope.launch {
            servers.servers
                .map { list -> list.any { requests.supports(it) } }
                .distinctUntilChanged()
                .collect { linked ->
                    val work = WorkManager.getInstance(context)
                    if (linked) {
                        val request = PeriodicWorkRequestBuilder<InboxPollWorker>(15, TimeUnit.MINUTES)
                            .setConstraints(constraints)
                            .build()
                        work.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
                    } else {
                        work.cancelUniqueWork(PERIODIC_WORK)
                        work.cancelUniqueWork(NOW_WORK)
                    }
                }
        }
    }

    /** Poll once now (app open), if any server is continuo-linked. */
    fun pollOnOpen(scope: CoroutineScope) {
        scope.launch {
            if (servers.servers.first().none { requests.supports(it) }) return@launch
            val request = OneTimeWorkRequestBuilder<InboxPollWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW_WORK, ExistingWorkPolicy.KEEP, request)
        }
    }

    private companion object {
        const val PERIODIC_WORK = "continuo-inbox-poll"
        const val NOW_WORK = "continuo-inbox-poll-now"
    }
}
