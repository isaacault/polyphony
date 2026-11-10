package app.polyphony.core.domain.model

import kotlinx.serialization.Serializable

/** A service this phone scrobbles to directly (Settings, Playback, Scrobbling from this phone). */
@Serializable
enum class ScrobbleService { LISTENBRAINZ, LASTFM }

/**
 * A connected scrobbling account. [secret] is the ListenBrainz user token or the Last.fm session
 * key; it's kept like a server's password (the app's private database, left out of the cloud backup,
 * included in the user's own backup file) and never shown or logged.
 *
 * [needsReconnect]: the service refused [secret] (a revoked token or session); plays wait in the
 * queue until the account is connected again.
 */
@Serializable
data class ScrobbleAccount(
    val service: ScrobbleService,
    val username: String,
    val secret: String,
    val nowPlaying: Boolean = true,
    val needsReconnect: Boolean = false,
) {
    override fun toString(): String =
        "ScrobbleAccount(service=$service, username=$username, nowPlaying=$nowPlaying, needsReconnect=$needsReconnect)"
}
