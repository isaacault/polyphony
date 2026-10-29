package app.polyphony.core.downloads.work

import app.polyphony.core.domain.repository.TransferRate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The running worker's [TransferRate], for the Downloads screen; null when nothing downloads. */
@Singleton
class DownloadRates @Inject constructor() {
    private val current = MutableStateFlow<TransferRate?>(null)
    val rate: StateFlow<TransferRate?> = current.asStateFlow()

    fun publish(rate: TransferRate?) {
        current.value = rate
    }
}
