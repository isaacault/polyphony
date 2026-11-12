package app.polyphony.core.domain.repository

import app.polyphony.core.domain.model.ScrobbleAccount
import app.polyphony.core.domain.model.ScrobbleService
import app.polyphony.core.domain.model.Track
import kotlinx.coroutines.flow.Flow

/**
 * What playback tells the server and the scrobblers: a track started, a play counted
 * ([app.polyphony.core.domain.playback.ScrobbleRule]). Neither may block or throw.
 *
 * The server is told first when [toServer] (the "report plays" setting): Navidrome for a library
 * song, continuo for a Play now song. It scrobbles to the accounts linked there, so this phone's own
 * accounts get only what the server didn't cover; a service linked in both places hears a play
 * once. A counted play is kept on the phone until the server has it (offline, it waits with the
 * time it was played), and only then goes on to this phone's accounts.
 */
interface Scrobbler {
    suspend fun nowPlaying(track: Track, toServer: Boolean)
    suspend fun played(track: Track, playedAtEpochMs: Long, toServer: Boolean)

    companion object {
        val NONE = object : Scrobbler {
            override suspend fun nowPlaying(track: Track, toServer: Boolean) = Unit
            override suspend fun played(track: Track, playedAtEpochMs: Long, toServer: Boolean) = Unit
        }
    }
}

/** The connected accounts, as the backup file carries them (as it carries server passwords). */
interface ScrobbleAccounts {
    suspend fun all(): List<ScrobbleAccount>
    suspend fun restore(accounts: List<ScrobbleAccount>)

    companion object {
        val NONE = object : ScrobbleAccounts {
            override suspend fun all(): List<ScrobbleAccount> = emptyList()
            override suspend fun restore(accounts: List<ScrobbleAccount>) = Unit
        }
    }
}

/**
 * Scrobbling straight from this phone to ListenBrainz and Last.fm (#39), for what the server doesn't
 * scrobble itself. Opt-in: nothing is sent until an account is connected, and this is the only
 * traffic that leaves the user's own server.
 */
interface ScrobbleRepository : Scrobbler, ScrobbleAccounts {
    val accounts: Flow<List<ScrobbleAccount>>

    /** Plays waiting to be sent, per service. */
    val pending: Flow<Map<ScrobbleService, Int>>

    /** False when this build has no Last.fm API key: the Last.fm option is hidden. */
    val lastFmAvailable: Boolean

    /** Checks [token] with ListenBrainz and connects its user. Throws [ScrobbleAuthException] if refused. */
    suspend fun connectListenBrainz(token: String): ScrobbleAccount

    /**
     * Signs in to Last.fm (auth.getMobileSession) and keeps only the session key; the password is
     * sent to Last.fm alone and never stored. Throws [ScrobbleAuthException] if refused.
     */
    suspend fun connectLastFm(username: String, password: String): ScrobbleAccount

    /** Forgets the account and the plays still waiting for it. */
    suspend fun disconnect(service: ScrobbleService)

    suspend fun setNowPlaying(service: ScrobbleService, enabled: Boolean)
}

/** The service refused the credentials (a wrong token or password). */
class ScrobbleAuthException(message: String) : Exception(message)
