package app.polyphony.core.domain.provider

import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.AlbumDetail
import app.polyphony.core.domain.model.AlbumListType
import app.polyphony.core.domain.model.Artist
import app.polyphony.core.domain.model.ArtistDetail
import app.polyphony.core.domain.model.Capability
import app.polyphony.core.domain.model.Genre
import app.polyphony.core.domain.model.ItemKind
import app.polyphony.core.domain.model.Lyrics
import app.polyphony.core.domain.model.Playlist
import app.polyphony.core.domain.model.PlaylistDetail
import app.polyphony.core.domain.model.SearchResults
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerInfo
import app.polyphony.core.domain.model.ServerType
import app.polyphony.core.domain.model.StarredContent
import app.polyphony.core.domain.model.StreamRequest
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.transcode.TranscodeProfile

/**
 * Uniform contract every music backend implements (Subsonic, Jellyfin, ...).
 *
 * All suspend functions throw [ProviderException] on failure. Implementations must be safe to call
 * from any dispatcher and should perform I/O on [kotlinx.coroutines.Dispatchers.IO].
 * Every returned model carries [Server.id] in its `serverId` field.
 */
interface MediaProvider {
    val server: Server
    val capabilities: Set<Capability>

    suspend fun ping(): ServerInfo

    // ---- Artists ----
    suspend fun getArtists(): List<Artist>
    suspend fun getArtist(id: String): ArtistDetail

    // ---- Albums ----
    suspend fun getAlbums(
        type: AlbumListType,
        size: Int = 50,
        offset: Int = 0,
        genre: String? = null,
        fromYear: Int? = null,
        toYear: Int? = null,
    ): List<Album>
    suspend fun getAlbum(id: String): AlbumDetail

    // ---- Tracks ----
    /**
     * Pages through every track on the server (used for the local library sync). Providers that
     * cannot list tracks directly should throw [ProviderException.Unsupported]; the sync will then
     * walk albums instead.
     */
    suspend fun getAllTracks(size: Int, offset: Int): List<Track>
    suspend fun getTrack(id: String): Track
    suspend fun getRandomTracks(size: Int = 50, genre: String? = null, fromYear: Int? = null, toYear: Int? = null): List<Track>
    suspend fun getTracksByGenre(genre: String, size: Int = 100, offset: Int = 0): List<Track>
    suspend fun getSimilarTracks(trackId: String, count: Int = 50): List<Track>
    suspend fun getTopTracks(artist: Artist, count: Int = 20): List<Track>

    // ---- Playlists ----
    suspend fun getPlaylists(): List<Playlist>
    suspend fun getPlaylist(id: String): PlaylistDetail
    suspend fun createPlaylist(name: String, trackIds: List<String> = emptyList()): Playlist
    suspend fun updatePlaylist(
        id: String,
        name: String? = null,
        description: String? = null,
        isPublic: Boolean? = null,
        addTrackIds: List<String> = emptyList(),
        removeIndices: List<Int> = emptyList(),
    )
    suspend fun deletePlaylist(id: String)

    // ---- Genres, favourites, ratings, search ----
    suspend fun getGenres(): List<Genre>
    suspend fun getStarred(): StarredContent
    suspend fun setStarred(id: String, kind: ItemKind, starred: Boolean)
    suspend fun setRating(id: String, kind: ItemKind, rating: Int)
    suspend fun search(query: String, artistLimit: Int = 20, albumLimit: Int = 20, trackLimit: Int = 50): SearchResults

    // ---- Extras ----
    suspend fun getLyrics(track: Track): Lyrics?
    suspend fun reportNowPlaying(trackId: String)
    suspend fun reportPlayed(trackId: String, playedAtEpochMs: Long)

    // ---- URLs ----
    /** Build the stream URL for [track] honouring [profile]. Pure; must not perform I/O. */
    fun streamRequest(track: Track, profile: TranscodeProfile): StreamRequest
    /** Original file download, for offline caching. */
    fun downloadRequest(track: Track): StreamRequest
    /** Artwork URL for a cover-art id at roughly [sizePx] square. Pure. */
    fun artworkUrl(artworkId: String?, sizePx: Int = 600): String?
}

data class ConnectRequest(
    val type: ServerType,
    val name: String,
    val baseUrl: String,
    val username: String,
    val password: String,
    val options: Map<String, String> = emptyMap(),
)

/** One factory per [ServerType]; registered into a Hilt multibinding set. */
interface MediaProviderFactory {
    val type: ServerType

    /** Validate credentials, exchange them for a token where needed, and return a persistable [Server]. */
    suspend fun authenticate(request: ConnectRequest): Server

    fun create(server: Server): MediaProvider
}

sealed class ProviderException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unauthorized(message: String = "Invalid credentials", cause: Throwable? = null) : ProviderException(message, cause)
    class NotFound(message: String = "Not found", cause: Throwable? = null) : ProviderException(message, cause)
    class Network(message: String = "Network error", cause: Throwable? = null) : ProviderException(message, cause)
    class Server(val code: Int, message: String, cause: Throwable? = null) : ProviderException(message, cause)
    class Unsupported(message: String = "Not supported by this server") : ProviderException(message)
    class Unknown(message: String, cause: Throwable? = null) : ProviderException(message, cause)
}
