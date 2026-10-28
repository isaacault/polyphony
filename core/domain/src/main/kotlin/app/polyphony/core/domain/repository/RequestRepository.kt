package app.polyphony.core.domain.repository

import app.polyphony.core.domain.model.ArtistName
import app.polyphony.core.domain.model.CatalogAlbum
import app.polyphony.core.domain.model.CatalogAlbumDetail
import app.polyphony.core.domain.model.CatalogArtist
import app.polyphony.core.domain.model.CatalogArtistPage
import app.polyphony.core.domain.model.CatalogResults
import app.polyphony.core.domain.model.ContinuoAccount
import app.polyphony.core.domain.model.ContinuoDevice
import app.polyphony.core.domain.model.InboxPage
import app.polyphony.core.domain.model.MusicRequest
import app.polyphony.core.domain.model.PlayNowStream
import app.polyphony.core.domain.model.RemovedAlbum
import app.polyphony.core.domain.model.RequestAction
import app.polyphony.core.domain.model.RequestEvent
import app.polyphony.core.domain.model.RequestOutcome
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerOptions
import app.polyphony.core.domain.model.StreamRequest
import kotlinx.coroutines.flow.Flow

/**
 * Acquisition through a continuo server: its catalogue ("Everything") and album requests. Only
 * continuo-linked servers support it; every caller checks [supports] and shows nothing otherwise.
 * Failures are [app.polyphony.core.domain.provider.ProviderException]s.
 */
interface RequestRepository {
    fun supports(server: Server?): Boolean = server?.options?.get(ServerOptions.CONTINUO) == "true"

    /** Library and catalogue hits merged by the server, each with its availability. */
    /**
     * The catalogue and library search; [quick]: only the top few, most listened first, fast
     * enough to ask while typing (continuo's quick search).
     */
    suspend fun search(server: Server, query: String, limit: Int = 25, quick: Boolean = false): CatalogResults

    /**
     * The artist's albums, EPs and singles (MusicBrainz artist [artistMbid]), newest first, each
     * with its availability: library albums are in it too, marked in_library or partial.
     */
    suspend fun artistAlbums(server: Server, artistMbid: String): List<CatalogAlbum>

    /**
     * The page for catalogue artist [artistMbid]: picture, biography, top songs and similar
     * artists. Null when the server doesn't know the artist, or is too old to have artist pages.
     */
    suspend fun catalogArtist(server: Server, artistMbid: String): CatalogArtistPage?

    /**
     * The whole album library album [libraryAlbumId] is of (a single song filed alone, or an
     * album the library only partly has); null when the catalogue doesn't know it.
     */
    suspend fun libraryAlbumTracklist(server: Server, libraryAlbumId: String): app.polyphony.core.domain.model.LibraryAlbumTracklist?

    /**
     * A song's lyrics from continuo (LRCLIB, continuo#59): for one the library has none for, or
     * a Play now song; null when none are known or the server is too old to say.
     */
    suspend fun lyrics(server: Server, artist: String, title: String, album: String?, durationMs: Long?): app.polyphony.core.domain.model.Lyrics?

    /** Where Play now songs are scrobbled; null from a continuo too old to scrobble them. */
    suspend fun scrobbling(server: Server): app.polyphony.core.domain.model.ScrobbleLinks?

    /** Link ListenBrainz with the account's user token; throws when the token is refused. */
    suspend fun linkListenBrainz(server: Server, token: String): app.polyphony.core.domain.model.ScrobbleLinks

    /** Start linking Last.fm: (token, the URL where the user approves it). */
    suspend fun startLastFm(server: Server): Pair<String, String>

    /** Finish linking Last.fm once approved; throws when it isn't yet. */
    suspend fun finishLastFm(server: Server, token: String): app.polyphony.core.domain.model.ScrobbleLinks

    suspend fun unlinkScrobbler(server: Server, service: String): app.polyphony.core.domain.model.ScrobbleLinks

    /** Scrobble a Play now song: a finished listen at [listenedAtMs], or (null) playing now. */
    suspend fun scrobblePlayNow(server: Server, streamId: String, listenedAtMs: Long?)

    /** One of discovery's ListenBrainz playlists as songs; null when it's gone. */
    suspend fun discoveryPlaylist(server: Server, id: String): app.polyphony.core.domain.model.DiscoveryPlaylistPage?

    /** The library albums holding single songs (continuo's _singles), by album id; null when unknown. */
    suspend fun singleSongAlbums(server: Server): Set<String>?

    /** Home's discovery rows; null from a continuo too old to have them. */
    suspend fun discover(server: Server): app.polyphony.core.domain.model.Discovery?

    /**
     * The catalogue artist named [name] (case and punctuation aside), for a library artist the
     * server has no MusicBrainz id for; null when the catalogue has no such artist.
     */
    suspend fun findArtist(server: Server, name: String): CatalogArtist?

    /** One catalogue album (MusicBrainz release group) with its availability and tracklist. */
    suspend fun catalogAlbum(server: Server, releaseGroupId: String): CatalogAlbumDetail

    /** Who this install is signed in as, and what the server offers. */
    suspend fun account(server: Server): ContinuoAccount

    /**
     * Remove library album [libraryAlbumId] from the server: continuo stops Lidarr from fetching it
     * again and moves its files into its cache, where [restoreAlbum] can bring it back. Admins only.
     */
    suspend fun removeAlbum(server: Server, libraryAlbumId: String): RemovedAlbum

    /** Removed albums still restorable, newest first. */
    suspend fun removedAlbums(server: Server): List<RemovedAlbum>

    suspend fun restoreAlbum(server: Server, removedId: Long)

    /** Library artists known by another name than their original, for showing and searching both. */
    suspend fun artistNames(server: Server): List<ArtistName>

    /** Start fetching a song to play now (continuo#12); poll [playNowStatus] until it's playable. */
    suspend fun startPlayNow(
        server: Server,
        recordingId: String,
        albumReleaseGroupId: String?,
        title: String,
        artist: String,
        durationMs: Long?,
        /** True: fetched in turn behind the account's other queued Play now songs, not at once. */
        queued: Boolean = false,
    ): PlayNowStream

    suspend fun playNowStatus(server: Server, streamId: String): PlayNowStream

    /** The order the queue will play its Play now songs in, soonest first: continuo fetches in it. */
    suspend fun prioritisePlayNow(server: Server, streamIds: List<String>)

    /** Keep a played-now song: continuo files its copy into your library (nothing downloads again). */
    suspend fun keepPlayNow(server: Server, streamId: String)

    /** Where the player fetches a Play now song's audio, with this install's authorisation. */
    suspend fun playNowStream(server: Server, streamId: String): StreamRequest

    /** Request just this song (MusicBrainz recording) of the album [albumReleaseGroupId]. */
    suspend fun requestTrack(server: Server, recordingId: String, albumReleaseGroupId: String): RequestOutcome

    /** Request the album (MusicBrainz release group). */
    suspend fun requestAlbum(server: Server, releaseGroupId: String): RequestOutcome

    /** Requests visible to this account (admins see everyone's), newest first, live pipelines. */
    suspend fun requests(server: Server, activeOnly: Boolean = false): List<MusicRequest>

    /** Live updates while collected; reconnects on its own and emits [RequestEvent.Resync] after. */
    fun events(server: Server): Flow<RequestEvent>

    /** Run [action] on a request; returns continuo's one-line confirmation. */
    suspend fun perform(server: Server, requestId: Long, action: RequestAction): String

    /** Devices signed in to the server's continuo account; [ContinuoDevice.current] is this install. */
    suspend fun devices(server: Server): List<ContinuoDevice>

    /** Sign [deviceId] out of continuo: its requests and its playback stop working. */
    suspend fun revokeDevice(server: Server, deviceId: String)

    /** Sign out every device on the account except this one; returns how many were signed out. */
    suspend fun revokeOtherDevices(server: Server): Int

    /**
     * Sign this install's continuo device out (`POST /v1/auth/logout`), e.g. because its server is
     * being removed (#120). Best-effort: bounded by a short timeout, never throws (other than
     * cancellation), and does nothing for a server without a session here.
     */
    suspend fun signOut(server: Server)

    /**
     * The account's notifications with an id above [since], at most [limit] (continuo caps it at
     * 500): the oldest ones, oldest first; page with [InboxPage.cursor] until a page comes back
     * short. With [newestFirst], the newest ones, newest first (`order=desc`), skipping any older
     * ones beyond [limit]; a server too old to know `order` ignores it and answers oldest first.
     */
    suspend fun notifications(
        server: Server,
        since: Long = 0,
        unreadOnly: Boolean = false,
        limit: Int = 100,
        newestFirst: Boolean = false,
    ): InboxPage

    /** Mark these notifications read; returns the inbox's unread count afterwards. */
    suspend fun markRead(server: Server, ids: List<Long>): Int

    /** Mark every notification read; returns the unread count afterwards (0 unless one just arrived). */
    suspend fun markAllRead(server: Server): Int
}
