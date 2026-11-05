package app.polyphony.core.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

/**
 * Keeps the CPU and Wi-Fi up while a renderer plays. Local playback gets this from ExoPlayer's
 * wake mode, but while casting ExoPlayer is idle, and with the screen off the phone would sleep
 * between the engine's polls: end-of-track and hand-off detection then run minutes late.
 */
internal class DlnaKeepAwake(context: Context) {
    private val wakeLock: PowerManager.WakeLock? =
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Polyphony:dlna")
            ?.apply { setReferenceCounted(false) }

    // Media3's WifiLockManager uses the same mode for streaming with the screen off.
    @Suppress("DEPRECATION")
    private val wifiLock: WifiManager.WifiLock? =
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Polyphony:dlna")
            ?.apply { setReferenceCounted(false) }

    /** Hold or release both locks; holding again renews the wake lock's timeout. */
    @Synchronized
    fun hold(active: Boolean) {
        runCatching {
            if (active) {
                wakeLock?.acquire(TIMEOUT_MS)
                wifiLock?.takeUnless { it.isHeld }?.acquire()
            } else {
                wakeLock?.takeIf { it.isHeld }?.release()
                wifiLock?.takeIf { it.isHeld }?.release()
            }
        }
    }

    private companion object {
        /** Renewed on every track change, so this only bounds a session that was never released. */
        const val TIMEOUT_MS = 2 * 60 * 60 * 1000L
    }
}
