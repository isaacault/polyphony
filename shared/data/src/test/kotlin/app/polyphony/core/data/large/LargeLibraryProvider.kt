package app.polyphony.core.data.large

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
import app.polyphony.core.domain.transcode.TranscodeProfile

/**
 * A server holding [library], answering the calls the sync engine makes the way Navidrome does
 * (albums alphabetically, tracks in pages). The library is mutable so a test can change the
 * server between two syncs: remove albums, add new ones, retag.
 */
class LargeLibraryProvider(library: LargeLibrary) : MediaProvider {
    override val server = Server(library.serverId, ServerType.SUBSONIC, "Large", "http://large", "u", "p", null, "dev")
    override val capabilities: Set<Capability> = emptySet()

    var artists: List<Artist> = library.artists
    var albums: List<Album> = library.albums
    var tracks: List<Track> = library.tracks
    var playlists: List<Pair<Playlist, List<Track>>> = library.playlists
    var genres: List<Genre> = library.genres

    private val alphabetical get() = albums.sortedBy { it.name.lowercase() }
    private val byAlbum get() = tracks.groupBy { it.albumId }

    /** Drops [albumIds] and their tracks from the server. */
    fun removeAlbums(albumIds: Set<String>) {
        albums = albums.filterNot { it.id in albumIds }
        tracks = tracks.filterNot { it.albumId in albumIds }
    }

    /** Drops single tracks (a file deleted from an album that stays). */
    fun removeTracks(trackIds: Set<String>) {
        tracks = tracks.filterNot { it.id in trackIds }
    }

    /** Adds [album] with [albumTracks], as the newest album on the server. */
    fun addAlbum(album: Album, albumTracks: List<Track>) {
        albums = albums + album.copy(trackCount = albumTracks.size)
        tracks = tracks + albumTracks
    }

    override suspend fun ping() = ServerInfo("large", "1")
    override suspend fun getArtists(): List<Artist> = artists
    override suspend fun getArtist(id: String) = ArtistDetail(artists.first { it.id == id }, albums.filter { it.artistId == id })

    override suspend fun getAlbums(type: AlbumListType, size: Int, offset: Int, genre: String?, fromYear: Int?, toYear: Int?): List<Album> {
        val ordered = when (type) {
            AlbumListType.RECENTLY_ADDED -> albums.sortedByDescending { it.createdAt }
            else -> alphabetical
        }
        return ordered.drop(offset).take(size)
    }

    override suspend fun getAlbum(id: String): AlbumDetail = AlbumDetail(albums.first { it.id == id }, byAlbum[id].orEmpty())
    override suspend fun getAllTracks(size: Int, offset: Int): List<Track> = tracks.subList(offset.coerceAtMost(tracks.size), (offset + size).coerceAtMost(tracks.size))
    override suspend fun getTrack(id: String): Track = tracks.first { it.id == id }
    override suspend fun getRandomTracks(size: Int, genre: String?, fromYear: Int?, toYear: Int?) = tracks.shuffled().take(size)
    override suspend fun getTracksByGenre(genre: String, size: Int, offset: Int) = tracks.filter { it.genre == genre }.drop(offset).take(size)
    override suspend fun getSimilarTracks(trackId: String, count: Int) = emptyList<Track>()
    override suspend fun getTopTracks(artist: Artist, count: Int) = emptyList<Track>()
    override suspend fun getPlaylists(): List<Playlist> = playlists.map { it.first }
    /** A playlist's songs that are still on the server, as a real one reports them. */
    override suspend fun getPlaylist(id: String): PlaylistDetail {
        val (playlist, members) = playlists.first { it.first.id == id }
        val present = tracks.mapTo(HashSet()) { it.id }
        return PlaylistDetail(playlist, members.filter { it.id in present })
    }
    override suspend fun createPlaylist(name: String, trackIds: List<String>) = Playlist("new", server.id, name)
    override suspend fun updatePlaylist(id: String, name: String?, description: String?, isPublic: Boolean?, addTrackIds: List<String>, removeIndices: List<Int>) = Unit
    override suspend fun deletePlaylist(id: String) = Unit
    override suspend fun getGenres(): List<Genre> = genres
    override suspend fun getStarred() = StarredContent(artists.filter { it.starred }, albums.filter { it.starred }, tracks.filter { it.starred })
    override suspend fun setStarred(id: String, kind: ItemKind, starred: Boolean) = Unit
    override suspend fun setRating(id: String, kind: ItemKind, rating: Int) = Unit
    override suspend fun search(query: String, artistLimit: Int, albumLimit: Int, trackLimit: Int) = SearchResults()
    override suspend fun getLyrics(track: Track): Lyrics? = null
    override suspend fun reportNowPlaying(trackId: String) = Unit
    override suspend fun reportPlayed(trackId: String, playedAtEpochMs: Long) = Unit
    override fun streamRequest(track: Track, profile: TranscodeProfile) = StreamRequest("http://large/stream/${track.id}")
    override fun downloadRequest(track: Track) = StreamRequest("http://large/download/${track.id}")
    override fun artworkUrl(artworkId: String?, sizePx: Int): String? = artworkId?.let { "http://large/art/$it" }
}
