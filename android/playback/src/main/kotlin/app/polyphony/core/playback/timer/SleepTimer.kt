package app.polyphony.core.playback.timer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pauses playback at a wall-clock time, optionally letting the current track finish first.
 *
 * The music fades out over the final [fadeMs] instead of cutting off: before the set time, or, in
 * "finish the current track" mode, before the end of the track ([remainingInTrackMs] says how far
 * that is). The factor it sets is the sleep timer's own (see OutputVolume): back to full when the
 * timer fires, is cancelled or is extended.
 */
class SleepTimer(
    private val scope: CoroutineScope,
    private val pause: () -> Unit,
    /** The sleep timer's share of the volume (0f..1f). */
    private val setVolume: (Float) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val fadeMs: Long = 30_000L,
    private val tickMs: Long = 200L,
    /** How long until the current track ends, or null when that isn't known (nothing playing, no length). */
    private val remainingInTrackMs: () -> Long? = { null },
) {
    private val _endsAtEpochMs = MutableStateFlow<Long?>(null)
    val endsAtEpochMs: StateFlow<Long?> = _endsAtEpochMs

    var finishTrack: Boolean = false
        private set
    private var job: Job? = null

    /** Waiting for the current track to end before pausing. */
    var waitingForTrackEnd = false
        private set

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
                fadeToTrackEnd()
            } else {
                runTimedCountdown(endsAtEpochMs)
            }
        }
    }

    /** The notification's quick extend: [byMs] more from the set time (or from now, if it's passed). */
    fun extend(byMs: Long) {
        val ends = _endsAtEpochMs.value ?: return
        set(maxOf(ends, clock()) + byMs, finishTrack)
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

    /** Fades over the end of the track; the track change itself pauses ([onTrackChanged]). */
    private suspend fun fadeToTrackEnd() {
        while (scope.isActive && waitingForTrackEnd) {
            val remaining = remainingInTrackMs()
            if (remaining != null && remaining < fadeMs) setVolume((remaining.toFloat() / fadeMs).coerceIn(0f, 1f))
            delay(tickMs)
        }
    }

    /** Call on media item transitions so "finish current track" can complete. */
    fun onTrackChanged() {
        if (waitingForTrackEnd) fire()
    }

    private fun fire() {
        job?.cancel()
        waitingForTrackEnd = false
        _endsAtEpochMs.value = null
        pause()
        restoreVolume()
    }

    private fun restoreVolume() = setVolume(1f)

    fun cancel() = set(null)
}
