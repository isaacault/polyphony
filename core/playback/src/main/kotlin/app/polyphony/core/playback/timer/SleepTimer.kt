package app.polyphony.core.playback.timer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Pauses playback at a wall-clock time, optionally letting the current track finish first. */
class SleepTimer(private val scope: CoroutineScope, private val pause: () -> Unit) {
    private val _endsAtEpochMs = MutableStateFlow<Long?>(null)
    val endsAtEpochMs: StateFlow<Long?> = _endsAtEpochMs

    var finishTrack: Boolean = false
        private set
    private var job: Job? = null
    private var waitingForTrackEnd = false

    fun set(endsAtEpochMs: Long?, finishTrack: Boolean = false) {
        job?.cancel()
        waitingForTrackEnd = false
        this.finishTrack = finishTrack
        _endsAtEpochMs.value = endsAtEpochMs
        if (endsAtEpochMs == null) return
        job = scope.launch {
            val wait = endsAtEpochMs - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            if (finishTrack) {
                waitingForTrackEnd = true
            } else {
                fire()
            }
        }
    }

    /** Call on media item transitions so "finish current track" can complete. */
    fun onTrackChanged() {
        if (waitingForTrackEnd) fire()
    }

    private fun fire() {
        waitingForTrackEnd = false
        _endsAtEpochMs.value = null
        pause()
    }

    fun cancel() = set(null)
}
