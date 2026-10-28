package app.polyphony.core.downloads.engine

import app.polyphony.core.domain.repository.TransferRate
import java.util.Locale

/**
 * Speed and time left for one worker run: [add] counts bytes as they arrive (from any download
 * thread), [sample] turns them and the run's overall progress into a [TransferRate], smoothed
 * over a few seconds so one slow chunk doesn't swing it. Time left follows overall progress, so
 * queued songs of unknown size count too.
 */
class TransferMeter(private val clock: () -> Long = System::currentTimeMillis) {
    private var bytes = 0L
    private var lastAt = Long.MIN_VALUE
    private var lastBytes = 0L
    private var lastFraction = 0f
    private var bytesPerSecond = -1.0
    private var fractionPerSecond = -1.0

    @Synchronized fun add(count: Long) {
        bytes += count
    }

    /** The estimate after this sample, or null until a second's worth has been measured. */
    @Synchronized fun sample(fraction: Float): TransferRate? {
        val now = clock()
        if (lastAt == Long.MIN_VALUE) {
            lastAt = now; lastBytes = bytes; lastFraction = fraction
            return null
        }
        val dt = (now - lastAt) / 1000.0
        if (dt >= MIN_INTERVAL_S) {
            bytesPerSecond = smooth(bytesPerSecond, (bytes - lastBytes) / dt)
            fractionPerSecond = smooth(fractionPerSecond, (fraction - lastFraction).coerceAtLeast(0f) / dt)
            lastAt = now; lastBytes = bytes; lastFraction = fraction
        }
        if (bytesPerSecond < 0) return null
        val left = if (fractionPerSecond > 0) ((1 - fraction).coerceAtLeast(0f) / fractionPerSecond).toLong() else null
        return TransferRate(bytesPerSecond.toLong(), left)
    }

    private fun smooth(previous: Double, current: Double) =
        if (previous < 0) current else previous + ALPHA * (current - previous)

    companion object {
        private const val MIN_INTERVAL_S = 1.0
        private const val ALPHA = 0.3

        /** "1.2 MB/s · 3 min left" for the notification (the app has its own, translatable). */
        fun describe(rate: TransferRate): String {
            val speed = rate.bytesPerSecond.toDouble()
            val speedText = when {
                speed >= 1_000_000 -> String.format(Locale.US, "%.1f MB/s", speed / 1_000_000)
                else -> String.format(Locale.US, "%.0f KB/s", speed / 1_000)
            }
            val left = rate.secondsLeft ?: return speedText
            val leftText = when {
                left >= 3600 -> "${left / 3600} h ${(left % 3600) / 60} min left"
                left >= 60 -> "${(left + 30) / 60} min left"
                else -> "under a minute left"
            }
            return "$speedText · $leftText"
        }
    }
}
