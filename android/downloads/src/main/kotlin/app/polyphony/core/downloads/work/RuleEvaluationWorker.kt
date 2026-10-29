package app.polyphony.core.downloads.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.core.downloads.engine.DownloadRuleEvaluator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Periodic re-evaluation of download rules for the active server (rules read its library). */
@HiltWorker
class RuleEvaluationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val evaluator: DownloadRuleEvaluator,
    private val serverRepository: ServerRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val server = serverRepository.getActive() ?: return Result.success()
        return runCatching { evaluator.evaluate(server.id) }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }
}
