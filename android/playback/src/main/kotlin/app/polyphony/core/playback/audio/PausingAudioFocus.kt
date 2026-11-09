package app.polyphony.core.playback.audio

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player

/**
 * Audio focus when "Lower volume for notifications" is off: a navigation prompt or a notification
 * pauses the music instead of ducking it, and it carries on when they're done. Media3 always ducks
 * for those, so with the setting off it hands focus over to this ([enabled]); a call or another
 * app's music pauses it either way, as Media3 does.
 */
class PausingAudioFocus(context: Context, private val player: Player) : AudioManager.OnAudioFocusChangeListener {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener(this, Handler(Looper.getMainLooper()))
        .build()

    private var held = false
    /** Paused for a short interruption: play again when it's over. */
    private var resumeOnGain = false

    var enabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value && player.playWhenReady) acquire() else if (!value) abandon()
        }

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!enabled) return
            if (playWhenReady) acquire() else if (!resumeOnGain) abandon()
        }
    }

    init {
        player.addListener(listener)
    }

    fun release() {
        player.removeListener(listener)
        abandon()
    }

    private fun acquire() {
        resumeOnGain = false
        if (held) return
        held = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!held) player.pause()
    }

    private fun abandon() {
        resumeOnGain = false
        if (!held) return
        audioManager.abandonAudioFocusRequest(request)
        held = false
    }

    override fun onAudioFocusChange(change: Int) {
        if (!enabled) return
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnGain) {
                resumeOnGain = false
                player.play()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> if (player.playWhenReady) {
                // Set before pausing, so the pause keeps the focus for coming back to.
                resumeOnGain = true
                player.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnGain = false
                player.pause()
                abandon()
            }
        }
    }
}
