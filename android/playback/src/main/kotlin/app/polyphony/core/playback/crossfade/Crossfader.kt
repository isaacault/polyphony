package app.polyphony.core.playback.crossfade

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.polyphony.core.playback.audio.OutputVolume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Crossfade on this phone's player. ExoPlayer can't play two songs at once, so a second player
 * plays the outgoing song's last seconds while the main player has already moved on to the next:
 *
 * 1. a few seconds before the fade, the second player opens the current song at the fade's start
 *    (from the stream cache, which the main player filled), silent and paused;
 * 2. at the fade's start it takes over the current song at full level, and the main player moves
 *    to the next song at zero;
 * 3. through the fade the second player goes down and the main one up (equal-power curves), then
 *    the second player stops.
 *
 * The main player keeps the queue, the session and everything that follows it (scrobbles, history,
 * the notification) as for any other change of song. Anything the user does meanwhile (pause, seek,
 * another song) ends the fade at once, the main player at full level. Each player keeps its song's
 * ReplayGain level, and the sleep timer's fade applies to both.
 */
@UnstableApi
class Crossfader(
    private val scope: CoroutineScope,
    private val main: ExoPlayer,
    private val mainVolume: OutputVolume,
    private val buildFader: () -> ExoPlayer,
    /** The next song and the fade into it, or null for no crossfade now (see CrossfadePlan). */
    private val plan: () -> Plan?,
) {
    data class Plan(val nextIndex: Int, val fadeMs: Long)

    private enum class Stage { IDLE, READY, FADING }

    private var fader: ExoPlayer? = null
    private val faderVolume = OutputVolume { level -> fader?.volume = level }
    private var stage = Stage.IDLE
    private var job: Job? = null

    private var fromIndex = C.INDEX_UNSET
    private var fromItem: MediaItem? = null
    private var toIndex = C.INDEX_UNSET
    private var toItem: MediaItem? = null
    private var fadeMs = 0L
    private var fadeStartMs = 0L
    private var fadingSince = 0L
    /** The main player's change of song is ours, not the user's. */
    private var movingOn = false

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (movingOn) {
                movingOn = false
                return
            }
            if (stage != Stage.IDLE) stopFade("the song changed")
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK && !movingOn && stage != Stage.IDLE) stopFade("a seek")
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && stage != Stage.IDLE) stopFade("paused")
        }
    }

    fun start() {
        main.addListener(listener)
        job = scope.launch {
            while (isActive) {
                runCatching { tick() }.onFailure { Log.w(TAG, "crossfade tick failed: ${it.message}"); stopFade("an error") }
                // Quick through the fade and the hand-over; at rest, a few looks a second.
                delay(if (stage == Stage.IDLE) 250 else 40)
            }
        }
    }

    fun release() {
        job?.cancel()
        main.removeListener(listener)
        stopFade(null)
        fader?.release()
        fader = null
    }

    /** The sleep timer's share, for the second player too. */
    fun setSleep(level: Float) {
        faderVolume.sleep = level
    }

    private fun tick() {
        when (stage) {
            Stage.IDLE -> maybePrepare()
            Stage.READY -> maybeHandOver()
            Stage.FADING -> fade()
        }
    }

    private fun maybePrepare() {
        if (!main.isPlaying) return
        val duration = main.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        val position = main.currentPosition
        val p = plan() ?: return
        val start = CrossfadePlan.fadeStartMs(duration, p.fadeMs)
        if (position < CrossfadePlan.prepareAtMs(duration, p.fadeMs) || position > start - MIN_LEAD_MS) return
        val item = main.currentMediaItem ?: return
        val next = main.takeIf { p.nextIndex in 0 until it.mediaItemCount }?.getMediaItemAt(p.nextIndex) ?: return
        val f = fader ?: buildFader().also { fader = it }
        faderVolume.fade = 0f
        f.setMediaItem(item, start)
        f.setPlaybackParameters(main.playbackParameters)
        f.playWhenReady = false
        f.prepare()
        fromIndex = main.currentMediaItemIndex
        fromItem = item
        toIndex = p.nextIndex
        toItem = next
        fadeMs = p.fadeMs
        fadeStartMs = start
        stage = Stage.READY
    }

    private fun maybeHandOver() {
        val f = fader ?: return stopFade("no second player")
        if (main.currentMediaItemIndex != fromIndex || main.currentMediaItem?.mediaId != fromItem?.mediaId) return stopFade("the song changed")
        val position = main.currentPosition
        if (position < fadeStartMs) return
        if (f.playbackState != Player.STATE_READY) {
            // It didn't open in time (the network, a stream the cache doesn't have): a plain change of song.
            if (position > fadeStartMs + HAND_OVER_GRACE_MS) stopFade("the second player wasn't ready")
            return
        }
        // The queue may have changed since: only move on to the song planned.
        val next = toItem
        if (next == null || toIndex >= main.mediaItemCount || main.getMediaItemAt(toIndex).mediaId != next.mediaId) return stopFade("the queue changed")
        faderVolume.gain = mainVolume.gain
        faderVolume.sleep = mainVolume.sleep
        faderVolume.fade = 1f
        f.play()
        mainVolume.fade = 0f
        movingOn = true
        main.seekTo(toIndex, 0)
        fadingSince = SystemClock.elapsedRealtime()
        stage = Stage.FADING
        Log.i(TAG, "crossfading into \"${next.mediaMetadata.title}\" over $fadeMs ms")
    }

    private fun fade() {
        val speed = main.playbackParameters.speed
        val t = ((SystemClock.elapsedRealtime() - fadingSince) * speed / fadeMs).coerceIn(0f, 1f)
        mainVolume.fade = CrossfadePlan.fadeIn(t)
        faderVolume.fade = CrossfadePlan.fadeOut(t)
        if (t >= 1f) stopFade(null)
    }

    private fun stopFade(why: String?) {
        if (why != null && stage != Stage.IDLE) Log.i(TAG, "crossfade stopped: $why")
        stage = Stage.IDLE
        movingOn = false
        fader?.let {
            it.stop()
            it.clearMediaItems()
        }
        faderVolume.fade = 0f
        mainVolume.fade = 1f
        fromItem = null
        toItem = null
    }

    private companion object {
        const val TAG = "PolyphonyCrossfade"
        /** Too close to the fade to open the song in time: this change of song goes without. */
        const val MIN_LEAD_MS = 1_000L
        const val HAND_OVER_GRACE_MS = 1_500L
    }
}
