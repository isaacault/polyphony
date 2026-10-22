package app.polyphony.core.domain.repository

import app.polyphony.core.domain.model.CatalogAlbum
import app.polyphony.core.domain.model.CatalogAlbumDetail
import app.polyphony.core.domain.model.CatalogArtist
import app.polyphony.core.domain.model.CatalogArtistPage
import app.polyphony.core.domain.model.CatalogResults
import app.polyphony.core.domain.model.ContinuoDevice
import app.polyphony.core.domain.model.InboxPage
import app.polyphony.core.domain.model.MusicRequest
import app.polyphony.core.domain.model.RequestAction
import app.polyphony.core.domain.model.RequestEvent
import app.polyphony.core.domain.model.RequestOutcome
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerOptions
import kotlinx.coroutines.flow.Flow

/**
 * Acquisition through a continuo server: its catalogue ("Everything") and album requests. Only
 * continuo-linked servers support it; every caller checks [supports] and shows nothing otherwise.
 * Failures are [app.polyphony.core.domain.provider.ProviderException]s.
 */
interface RequestRepository {
    fun supports(server: Server?): Boolean = server?.options?.get(ServerOptions.CONTINUO) == "true"

    /** Library and catalogue hits merged by the server, each with its availability. */
    suspend fun search(server: Server, query: String, limit: Int = 25): CatalogResults

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
     * The catalogue artist named [name] (case and punctuation aside), for a library artist the
     * server has no MusicBrainz id for; null when the catalogue has no such artist.
     */
    suspend fun findArtist(server: Server, name: String): CatalogArtist?

    /** One catalogue album (MusicBrainz release group) with its availability and tracklist. */
    suspend fun catalogAlbum(server: Server, releaseGroupId: String): CatalogAlbumDetail

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
