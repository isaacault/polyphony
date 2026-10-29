package app.polyphony.core.playback.timer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Pauses playback at a wall-clock time, optionally letting the current track finish first.
 *
 * A timed sleep fades the volume down over its final [fadeMs] so it does not cut out abruptly;
 * the volume is always restored to full when the timer fires or is cancelled. "Finish the current
 * track" mode does not fade, since its end point is the next track transition, not a known time.
 */
class SleepTimer(
    private val scope: CoroutineScope,
    private val pause: () -> Unit,
    /** Sets the playback volume (0f..1f); defaults to a no-op so callers without volume still work. */
    private val setVolume: (Float) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val fadeMs: Long = 8_000L,
    private val tickMs: Long = 200L,
) {
    private val _endsAtEpochMs = MutableStateFlow<Long?>(null)
    val endsAtEpochMs: StateFlow<Long?> = _endsAtEpochMs

    var finishTrack: Boolean = false
        private set
    private var job: Job? = null
    private var waitingForTrackEnd = false

    fun set(endsAtEpochMs: Long?, finishTrack: Boolean = false) {
        job?.cancel()
        waitingForTrackEnd = false
        restoreVolume()
        this.finishTrack = finishTrack
        _endsAtEpochMs.value = endsAtEpochMs
        if (endsAtEpochMs == null) return
        job = scope.launch {
            if (finishTrack) {
                val wait = endsAtEpochMs - clock()
                if (wait > 0) delay(wait)
                waitingForTrackEnd = true
            } else {
                runTimedCountdown(endsAtEpochMs)
            }
        }
    }

    /** Waits out the quiet stretch, then ramps the volume to zero over the final [fadeMs]. */
    private suspend fun runTimedCountdown(endsAtEpochMs: Long) {
        val beforeFade = endsAtEpochMs - fadeMs - clock()
        if (beforeFade > 0) delay(beforeFade)
        while (true) {
            val remaining = endsAtEpochMs - clock()
            if (remaining <= 0) break
            setVolume((remaining.toFloat() / fadeMs).coerceIn(0f, 1f))
            delay(tickMs.coerceAtMost(remaining))
        }
        fire()
    }

    /** Call on media item transitions so "finish current track" can complete. */
    fun onTrackChanged() {
        if (waitingForTrackEnd) fire()
    }

    private fun fire() {
        waitingForTrackEnd = false
        _endsAtEpochMs.value = null
        pause()
        restoreVolume()
    }

    private fun restoreVolume() = setVolume(1f)

    fun cancel() = set(null)
}
