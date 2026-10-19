package app.polyphony.provider.subsonic.internal

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

private val NO_COLON_OFFSET = Regex("([+-]\\d{2})(\\d{2})$")

/**
 * Parses the ISO-8601 timestamps Subsonic servers emit. Handles offsets (`Z`, `+02:00`, `+0200`),
 * missing fractions and timestamps with no zone (assumed UTC). Returns null when unparseable.
 */
internal fun parseIsoMillis(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    val text = value.trim().let { raw ->
        if (raw.contains('T') && NO_COLON_OFFSET.containsMatchIn(raw)) raw.replace(NO_COLON_OFFSET, "$1:$2") else raw
    }
    return try {
        OffsetDateTime.parse(text).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        try {
            LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                Instant.parse(text).toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}
