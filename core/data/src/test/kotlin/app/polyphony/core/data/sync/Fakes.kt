package app.polyphony.core.data.sync

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
import app.polyphony.core.domain.provider.MediaProvider
import app.polyphony.core.domain.provider.ProviderException
import app.polyphony.core.domain.transcode.TranscodeProfile

internal const val SERVER_ID = "srv"

internal fun artist(i: Int, albums: Int) = Artist(id = "ar$i", serverId = SERVER_ID, name = "Artist $i", albumCount = albums)
internal fun album(i: Int, tracks: Int) = Album(id = "al$i", serverId = SERVER_ID, name = "Album $i", artistId = "ar1", trackCount = tracks)
internal fun track(albumIndex: Int, n: Int) = Track(id = "t$albumIndex-$n", serverId = SERVER_ID, title = "Track $n", albumId = "al$albumIndex")

/** A scripted server: N albums with M tracks each; `supportsAllTracks` toggles the paged listing. */
internal open class FakeProvider(
    val albumCount: Int = 1_200,
    val tracksPerAlbum: Int = 2,
    val supportsAllTracks: Boolean = true,
    val playlists: List<Playlist> = emptyList(),
    val starred: StarredContent = StarredContent(),
) : MediaProvider {
    override val server = Server(SERVER_ID, ServerType.SUBSONIC, "Fake", "http://fake", "u", "p", null, "dev")
    override val capabilities: Set<Capability> = emptySet()

    val albumCalls = mutableListOf<Pair<AlbumListType, Int>>()
    val albumDetailCalls = mutableListOf<String>()
    var pings = 0

    private val allAlbums = (1..albumCount).map { album(it, tracksPerAlbum) }
    private val allTracks = (1..albumCount).flatMap { a -> (1..tracksPerAlbum).map { track(a, it) } }

    override suspend fun ping(): ServerInfo { pings++; return ServerInfo("fake", "1") }
    override suspend fun getArtists(): List<Artist> = listOf(artist(1, albumCount))
    override suspend fun getArtist(id: String) = ArtistDetail(artist(1, albumCount), allAlbums)
    override suspend fun getAlbums(type: AlbumListType, size: Int, offset: Int, genre: String?, fromYear: Int?, toYear: Int?): List<Album> {
        albumCalls += type to offset
        return allAlbums.drop(offset).take(size)
    }
    override suspend fun getAlbum(id: String): AlbumDetail {
        albumDetailCalls += id
        val index = id.removePrefix("al").toInt()
        return AlbumDetail(album(index, tracksPerAlbum), (1..tracksPerAlbum).map { track(index, it) })
    }
    override suspend fun getAllTracks(size: Int, offset: Int): List<Track> {
        if (!supportsAllTracks) throw ProviderException.Unsupported()
        return allTracks.drop(offset).take(size)
    }
    override suspend fun getTrack(id: String): Track = allTracks.first { it.id == id }
    override suspend fun getRandomTracks(size: Int, genre: String?, fromYear: Int?, toYear: Int?) = allTracks.take(size)
    override suspend fun getTracksByGenre(genre: String, size: Int, offset: Int) = emptyList<Track>()
    override suspend fun getSimilarTracks(trackId: String, count: Int) = emptyList<Track>()
    override suspend fun getTopTracks(artist: Artist, count: Int) = emptyList<Track>()
    override suspend fun getPlaylists(): List<Playlist> = playlists
    override suspend fun getPlaylist(id: String) = PlaylistDetail(playlists.first { it.id == id }, allTracks.take(3))
    override suspend fun createPlaylist(name: String, trackIds: List<String>) = Playlist("new", SERVER_ID, name)
    override suspend fun updatePlaylist(id: String, name: String?, description: String?, isPublic: Boolean?, addTrackIds: List<String>, removeIndices: List<Int>) = Unit
    override suspend fun deletePlaylist(id: String) = Unit
    override suspend fun getGenres(): List<Genre> = listOf(Genre("Rock", SERVER_ID, 1, 2))
    override suspend fun getStarred(): StarredContent = starred
    override suspend fun setStarred(id: String, kind: ItemKind, starred: Boolean) = Unit
    override suspend fun setRating(id: String, kind: ItemKind, rating: Int) = Unit
    override suspend fun search(query: String, artistLimit: Int, albumLimit: Int, trackLimit: Int) = SearchResults()
    override suspend fun getLyrics(track: Track): Lyrics? = null
    override suspend fun reportNowPlaying(trackId: String) = Unit
    override suspend fun reportPlayed(trackId: String, playedAtEpochMs: Long) = Unit
    override fun streamRequest(track: Track, profile: TranscodeProfile) = StreamRequest("http://fake/stream/${track.id}")
    override fun downloadRequest(track: Track) = StreamRequest("http://fake/download/${track.id}")
    override fun artworkUrl(artworkId: String?, sizePx: Int): String? = artworkId?.let { "http://fake/art/$it" }
}

/** Records everything the engine writes. */
internal class RecordingSink(initialAlbumCounts: Map<String, Int> = emptyMap()) : SyncSink {
    val artists = mutableListOf<Artist>()
    val albums = mutableListOf<Album>()
    val tracks = mutableListOf<Track>()
    val trackWrites = mutableListOf<Int>()
    val playlists = mutableMapOf<String, List<Track>>()
    val generations = mutableSetOf<Long>()
    var starred: StarredContent? = null
    var genres: List<Genre> = emptyList()
    var staleDeletedAt: Long? = null
    var keptPlaylistIds: Collection<String>? = null
    private val albumCounts = initialAlbumCounts.toMutableMap()

    override suspend fun writeArtists(serverId: String, artists: List<Artist>, generation: Long) { this.artists += artists; generations += generation }
    override suspend fun writeAlbums(serverId: String, albums: List<Album>, generation: Long) { this.albums += albums; generations += generation; albums.forEach { albumCounts[it.id] = it.trackCount } }
    override suspend fun writeTracks(serverId: String, tracks: List<Track>, generation: Long) { this.tracks += tracks; trackWrites += tracks.size; generations += generation }
    override suspend fun writePlaylist(serverId: String, playlist: Playlist, tracks: List<Track>, generation: Long) { playlists[playlist.id] = tracks; generations += generation }
    override suspend fun deletePlaylistsExcept(serverId: String, keepIds: Collection<String>) { keptPlaylistIds = keepIds }
    override suspend fun applyStarred(serverId: String, starred: StarredContent, generation: Long) { this.starred = starred }
    override suspend fun writeGenres(serverId: String, genres: List<Genre>, generation: Long) { this.genres = genres }
    override suspend fun albumTrackCounts(serverId: String): Map<String, Int> = albumCounts.toMap()
    override suspend fun deleteStale(serverId: String, generation: Long) { staleDeletedAt = generation }
}
