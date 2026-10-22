package app.polyphony.core.domain.model

/**
 * One signed-in install on a continuo account (#119). Every sign-in (a new phone, a reinstall, a
 * restored backup) is its own device with its own app password; revoking one ends its requests
 * and its playback.
 */
data class ContinuoDevice(
    val id: String,
    val name: String,
    /** e.g. `android`, when the device said. */
    val platform: String?,
    /** Epoch millis, when the server sent a parseable timestamp. */
    val createdAtMillis: Long?,
    /** Epoch millis of the device's last refresh, when known. */
    val lastSeenAtMillis: Long?,
    /** The device making the call, i.e. this install. */
    val current: Boolean,
)
