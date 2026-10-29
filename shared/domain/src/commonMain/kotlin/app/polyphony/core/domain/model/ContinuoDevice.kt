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
    /**
     * The server says whether this account may remove albums: it lists library_removal when its
     * music folder is mounted and the account is allowed (every account, unless it keeps it to admins).
     */
    val canRemoveAlbums: Boolean get() = "library_removal" in capabilities

    /** The server can fetch a single song (from Soulseek) instead of its whole album. */
    val canRequestSongs: Boolean get() = "track_requests" in capabilities

    /** The server can fetch a song you don't have and play it while it downloads (continuo#12). */
    val canPlayNow: Boolean get() = "play_now" in capabilities
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

/** An artist the library knows by another name than its original (Ichiko Aoba / 青葉市子). */
data class ArtistName(val name: String, val originalName: String, val sortName: String?)
