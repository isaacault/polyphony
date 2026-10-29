package app.polyphony.core.ui.util

import java.util.Locale

/** "3:45" or "1:02:03". */
fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

/** "1 hr 12 min" / "45 min" / "30 sec". */
fun formatDurationLong(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    return when {
        h > 0 -> "$h hr ${m} min"
        m > 0 -> "$m min"
        else -> "${totalSeconds} sec"
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.US, if (value >= 100) "%.0f %s" else "%.1f %s", value, units[unit])
}

/** "FLAC · 24-bit/96 kHz" or "MP3 · 320 kbps". */
fun formatAudioQuality(container: String?, bitrateKbps: Int?, sampleRateHz: Int?, bitDepth: Int?): String? {
    val parts = mutableListOf<String>()
    container?.uppercase()?.let(parts::add)
    if (bitDepth != null && sampleRateHz != null) {
        parts.add("${bitDepth}-bit/${formatSampleRate(sampleRateHz)}")
    } else if (sampleRateHz != null) {
        parts.add(formatSampleRate(sampleRateHz))
    } else if (bitrateKbps != null && bitrateKbps > 0) {
        parts.add("$bitrateKbps kbps")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun formatSampleRate(hz: Int): String {
    val khz = hz / 1000.0
    return if (khz == khz.toInt().toDouble()) "${khz.toInt()} kHz" else String.format(Locale.US, "%.1f kHz", khz)
}
