package app.polyphony.core.playback.engine

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import app.polyphony.core.domain.transcode.AdaptiveLatencySettings
import app.polyphony.core.playback.util.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches rebuffer events and decides whether the connection is "degraded". Pure logic with an
 * injectable [Clock]; [LatencyPlayerListener] feeds it from a Media3 player.
 *
 * A stall is a rebuffer while playback was expected to continue (READY → BUFFERING with
 * playWhenReady, not caused by a seek or item change). The monitor trips when
 * [AdaptiveLatencySettings.stallThreshold] stalls start within [AdaptiveLatencySettings.windowSeconds],
 * or a single stall lasts at least [AdaptiveLatencySettings.longStallMs]. It recovers after
 * [AdaptiveLatencySettings.recoverAfterSeconds] without stalls (0 = manual), on [reset], or on
 * network change.
 */
@Singleton
class LatencyMonitor @Inject constructor(private val clock: Clock) {

    private val _degraded = MutableStateFlow(false)
    val degraded: StateFlow<Boolean> = _degraded.asStateFlow()

    @Volatile
    var settings: AdaptiveLatencySettings = AdaptiveLatencySettings()
        set(value) {
            field = value
            if (!value.enabled) reset()
        }

    private val lock = Any()
    private val stallStarts = ArrayDeque<Long>()
    private var ongoingStallStart: Long? = null
    private var lastStallActivityMs: Long = 0L
    private var longStallCounted = false

    /** Total stalls observed since the last reset (for diagnostics / UI). */
    var stallCount: Int = 0
        private set

    fun onStallStarted(nowMs: Long = clock.nowMs()) {
        if (!settings.enabled) return
        synchronized(lock) {
            ongoingStallStart = nowMs
            longStallCounted = false
            lastStallActivityMs = nowMs
            stallCount++
            stallStarts.addLast(nowMs)
            prune(nowMs)
            if (stallStarts.size >= settings.stallThreshold.coerceAtLeast(1)) {
                _degraded.value = true
            }
        }
    }

    fun onStallEnded(nowMs: Long = clock.nowMs()) {
        synchronized(lock) {
            val start = ongoingStallStart ?: return
            ongoingStallStart = null
            lastStallActivityMs = nowMs
            if (settings.enabled && nowMs - start >= settings.longStallMs) {
                _degraded.value = true
            }
        }
    }

    /** Call periodically (a few times a minute) to apply long-stall and recovery rules. */
    fun tick(nowMs: Long = clock.nowMs()) {
        synchronized(lock) {
            val start = ongoingStallStart
            if (start != null) {
                if (settings.enabled && !longStallCounted && nowMs - start >= settings.longStallMs) {
                    longStallCounted = true
                    _degraded.value = true
                }
                return
            }
            if (_degraded.value && settings.recoverAfterSeconds > 0) {
                if (nowMs - lastStallActivityMs >= settings.recoverAfterSeconds * 1000L) {
                    clearLocked()
                }
            }
        }
    }

    /** Clears history and the degraded flag (network change or user "Restore quality"). */
    fun reset() {
        synchronized(lock) { clearLocked() }
    }

    private fun clearLocked() {
        stallStarts.clear()
        ongoingStallStart = null
        longStallCounted = false
        _degraded.value = false
    }

    private fun prune(nowMs: Long) {
        val windowMs = settings.windowSeconds * 1000L
        while (stallStarts.isNotEmpty() && nowMs - stallStarts.first() > windowMs) {
            stallStarts.removeFirst()
        }
    }
}

/** Adapts [Player.Listener] callbacks into stall start/end events for [LatencyMonitor]. */
class LatencyPlayerListener(private val monitor: LatencyMonitor) : Player.Listener {
    private var lastState = Player.STATE_IDLE
    private var ignoreNextBuffering = false
    private var stalled = false

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) ignoreNextBuffering = true
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        ignoreNextBuffering = true
        if (stalled) {
            stalled = false
            monitor.onStallEnded()
        }
    }

    fun onPlaybackStateChanged(playbackState: Int, playWhenReady: Boolean) {
        when (playbackState) {
            Player.STATE_BUFFERING -> {
                if (lastState == Player.STATE_READY && playWhenReady && !ignoreNextBuffering) {
                    stalled = true
                    monitor.onStallStarted()
                }
            }
            Player.STATE_READY -> {
                ignoreNextBuffering = false
                if (stalled) {
                    stalled = false
                    monitor.onStallEnded()
                }
            }
            Player.STATE_IDLE, Player.STATE_ENDED -> {
                ignoreNextBuffering = false
                if (stalled) {
                    stalled = false
                    monitor.onStallEnded()
                }
            }
        }
        lastState = playbackState
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
            onPlaybackStateChanged(player.playbackState, player.playWhenReady)
        }
    }
}
