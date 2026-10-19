package app.polyphony.core.dlna.service

import java.util.Locale

/** UPnP `H:MM:SS[.mmm]` time strings. Tolerant parser; "NOT_IMPLEMENTED" and junk map to null. */
object UpnpTime {

    fun format(ms: Long, withMillis: Boolean = false): String {
        val total = ms.coerceAtLeast(0)
        val h = total / 3_600_000
        val m = (total % 3_600_000) / 60_000
        val s = (total % 60_000) / 1_000
        val millis = total % 1_000
        return if (withMillis) String.format(Locale.US, "%d:%02d:%02d.%03d", h, m, s, millis)
        else String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    }

    fun parse(value: String?): Long? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw.equals("NOT_IMPLEMENTED", true)) return null
        var text = raw
        var sign = 1
        if (text.startsWith("-")) { sign = -1; text = text.substring(1) }
        else if (text.startsWith("+")) text = text.substring(1)
        // Drop fractional-second expressed as "F0/F1" (rare) or ".mmm".
        var fractionMs = 0L
        val dot = text.indexOf('.')
        if (dot >= 0) {
            val fraction = text.substring(dot + 1)
            text = text.substring(0, dot)
            val slash = fraction.indexOf('/')
            fractionMs = if (slash >= 0) {
                // UPnP "F0/F1" fraction form, e.g. ".1/2" == half a second.
                val f0 = fraction.substring(0, slash).toLongOrNull()
                val f1 = fraction.substring(slash + 1).takeWhile { it.isDigit() }.toLongOrNull()
                if (f0 != null && f1 != null && f1 > 0) (f0 * 1_000) / f1 else 0
            } else {
                val digits = fraction.takeWhile { it.isDigit() }
                if (digits.isEmpty()) 0 else digits.padEnd(3, '0').take(3).toLong()
            }
        }
        val parts = text.split(':')
        if (parts.isEmpty() || parts.size > 3) return null
        val numbers = parts.map { it.trim().toLongOrNull() ?: return null }
        val seconds = when (numbers.size) {
            3 -> numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
            2 -> numbers[0] * 60 + numbers[1]
            else -> numbers[0]
        }
        return sign * (seconds * 1_000 + fractionMs)
    }
}
