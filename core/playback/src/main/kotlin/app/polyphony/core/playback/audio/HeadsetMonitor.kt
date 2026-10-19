package app.polyphony.core.playback.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * Resumes playback when headphones / a Bluetooth audio device connect (opt-in setting).
 * Pause-on-disconnect is handled by ExoPlayer's `handleAudioBecomingNoisy`.
 */
class HeadsetMonitor(context: Context, private val onOutputConnected: () -> Unit) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var registered = false

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (addedDevices.any { it.isSink && it.type in HEADSET_TYPES }) onOutputConnected()
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled && !registered) {
            audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
            registered = true
        } else if (!enabled && registered) {
            audioManager.unregisterAudioDeviceCallback(callback)
            registered = false
        }
    }

    private companion object {
        val HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }
}
