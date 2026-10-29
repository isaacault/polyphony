package app.polyphony.inbox

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Runs one [InboxPoller.pollAll]; scheduled by [InboxScheduler]. */
@HiltWorker
class InboxPollWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val poller: InboxPoller,
) : CoroutineWorker(appContext, params) {

    // A failed poll isn't retried: the next periodic run (or app open) picks up where it stopped.
    override suspend fun doWork(): Result {
        poller.pollAll()
        return Result.success()
    }
}
