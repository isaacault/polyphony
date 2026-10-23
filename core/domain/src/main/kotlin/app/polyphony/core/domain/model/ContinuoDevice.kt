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

/** The continuo account this install is signed in as (`GET /v1/me`). */
data class ContinuoAccount(
    val username: String,
    val role: String,
    val capabilities: Set<String>,
) {
    /** Removing albums deletes music for everyone, so it's for admins on a server that has it set up. */
    val canRemoveAlbums: Boolean get() = role == "admin" && "library_removal" in capabilities
}

/** An album removed from the library into continuo's media cache, restorable until [restorableUntilMillis]. */
data class RemovedAlbum(
    val id: Long,
    val title: String,
    val artist: String?,
    val removedAtMillis: Long?,
    val removedBy: String?,
    val restorableUntilMillis: Long?,
    val libraryAlbumId: String?,
)
