package app.polyphony.provider.subsonic

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
import app.polyphony.core.domain.model.StarredContent
import app.polyphony.core.domain.model.StreamRequest
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.provider.MediaProvider
import app.polyphony.core.domain.provider.ProviderException
import app.polyphony.core.domain.transcode.AudioCodec
import app.polyphony.core.domain.transcode.TranscodeProfile
import app.polyphony.provider.subsonic.api.SubsonicApi
import app.polyphony.provider.subsonic.api.SubsonicApi.Companion.booleanField
import app.polyphony.provider.subsonic.api.SubsonicApi.Companion.stringField
import app.polyphony.provider.subsonic.api.SubsonicApiException
import app.polyphony.provider.subsonic.api.subsonicCall
import app.polyphony.provider.subsonic.dto.AlbumDto
import app.polyphony.provider.subsonic.dto.AlbumListDto
import app.polyphony.provider.subsonic.dto.ArtistDto
import app.polyphony.provider.subsonic.dto.ArtistInfoDto
import app.polyphony.provider.subsonic.dto.ArtistsDto
import app.polyphony.provider.subsonic.dto.ChildDto
import app.polyphony.provider.subsonic.dto.ExtensionDto
import app.polyphony.provider.subsonic.dto.GenresDto
import app.polyphony.provider.subsonic.dto.LyricsDto
import app.polyphony.provider.subsonic.dto.LyricsListDto
import app.polyphony.provider.subsonic.dto.PlaylistDto
import app.polyphony.provider.subsonic.dto.PlaylistsDto
import app.polyphony.provider.subsonic.dto.SearchResultDto
import app.polyphony.provider.subsonic.dto.SongListDto
import app.polyphony.provider.subsonic.dto.StarredDto
import app.polyphony.provider.subsonic.internal.stripHtml
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Year

/**
 * [MediaProvider] for Subsonic-compatible servers (Navidrome, Airsonic-Advanced, Gonic,
 * LMS, Ampache's Subsonic API, original Subsonic). OpenSubsonic extensions are used when present.
 */
internal class SubsonicProvider(
    override val server: Server,
    private val api: SubsonicApi,
) : MediaProvider {

    private val mapper = SubsonicMapper(server.id, api.urls)

    @Volatile private var extensions: Set<String>? = null
    private val extensionsMutex = Mutex()

    @Volatile private var capabilitiesCache: Set<Capability> = BASE_CAPABILITIES
    override val capabilities: Set<Capability> get() = capabilitiesCache

    // ---------------------------------------------------------------- server

    override suspend fun ping(): ServerInfo = subsonicCall {
        val envelope = api.request("ping")
        val openSubsonic = envelope.booleanField("openSubsonic") ?: false
        val extensionNames = if (openSubsonic) loadExtensions() else emptySet()
        ServerInfo(
            productName = envelope.stringField("type") ?: "Subsonic",
            version = envelope.stringField("serverVersion") ?: envelope.stringField("version") ?: "unknown",
            apiVersion = envelope.stringField("version"),
            isOpenSubsonic = openSubsonic,
            extensions = extensionNames.sorted(),
        )
    }

    private suspend fun loadExtensions(): Set<String> {
        extensions?.let { return it }
        return extensionsMutex.withLock {
            extensions?.let { return it }
            val names = try {
                api.getOrNull<List<ExtensionDto>>("getOpenSubsonicExtensions", key = "openSubsonicExtensions")
                    ?.map { it.name }?.toSet().orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptySet()
            }
            extensions = names
            capabilitiesCache = if (EXT_SONG_LYRICS in names) BASE_CAPABILITIES + Capability.SYNCED_LYRICS else BASE_CAPABILITIES
            names
        }
    }

    // --------------------------------------------------------------- artists

    override suspend fun getArtists(): List<Artist> = subsonicCall {
        val dto = api.get<ArtistsDto>("getArtists", key = "artists")
        dto.index.asSequence().flatMap { it.artist.asSequence() }.map(mapper::artist).toList()
    }

    override suspend fun getArtist(id: String): ArtistDetail = subsonicCall {
        coroutineScope {
            val artistDeferred = async { api.get<ArtistDto>("getArtist", mapOf("id" to id), "artist") }
            val infoDeferred = async {
                optional { api.getOrNull<ArtistInfoDto>("getArtistInfo2", mapOf("id" to id, "count" to 20), "artistInfo2") }
            }
            val dto = artistDeferred.await()
            val info = infoDeferred.await()
            val base = mapper.artist(dto)
            val artist = base.copy(
                biography = info?.biography?.stripHtml()?.takeIf { it.isNotBlank() },
                artworkUrl = base.artworkUrl ?: info?.largeImageUrl?.takeIf { it.isNotBlank() } ?: info?.mediumImageUrl?.takeIf { it.isNotBlank() },
                musicBrainzId = base.musicBrainzId ?: info?.musicBrainzId,
                albumCount = if (dto.album.isNotEmpty()) dto.album.size else base.albumCount,
            )
            ArtistDetail(
                artist = artist,
                albums = dto.album.map(mapper::album).sortedWith(compareByDescending<Album> { it.year ?: 0 }.thenBy { it.name.lowercase() }),
                similarArtists = info?.similarArtist?.map(mapper::artist).orEmpty(),
            )
        }
    }

    // ---------------------------------------------------------------- albums

    override suspend fun getAlbums(
        type: AlbumListType,
        size: Int,
        offset: Int,
        genre: String?,
        fromYear: Int?,
        toYear: Int?,
    ): List<Album> = subsonicCall {
        val effectiveType = if (type == AlbumListType.BY_GENRE && genre.isNullOrBlank()) AlbumListType.ALPHABETICAL_BY_NAME else type
        val params = mutableMapOf<String, Any?>(
            "type" to SubsonicMapper.albumListType(effectiveType),
            "size" to size.coerceIn(1, MAX_PAGE),
            "offset" to offset.coerceAtLeast(0),
        )
        if (effectiveType == AlbumListType.BY_GENRE) params["genre"] = genre
        if (effectiveType == AlbumListType.BY_YEAR) {
            params["fromYear"] = fromYear ?: 0
            params["toYear"] = toYear ?: Year.now().value
        } else if (fromYear != null || toYear != null) {
            // Only meaningful for byYear, but harmless elsewhere.
            params["fromYear"] = fromYear
            params["toYear"] = toYear
        }
        api.getOrNull<AlbumListDto>("getAlbumList2", params, "albumList2")?.album?.map(mapper::album).orEmpty()
    }

    override suspend fun getAlbum(id: String): AlbumDetail = subsonicCall {
        val dto = api.get<AlbumDto>("getAlbum", mapOf("id" to id), "album")
        val tracks = mapper.tracks(dto.song).sortedWith(compareBy<Track>({ it.discNumber ?: 1 }, { it.trackNumber ?: 0 }))
        AlbumDetail(album = mapper.album(dto), tracks = tracks)
    }

    // ---------------------------------------------------------------- tracks

    override suspend fun getAllTracks(size: Int, offset: Int): List<Track> = subsonicCall {
        val result = try {
            api.getOrNull<SearchResultDto>(
                "search3",
                mapOf(
                    "query" to "",
                    "artistCount" to 0,
                    "albumCount" to 0,
                    "songCount" to size.coerceIn(1, MAX_PAGE),
                    "songOffset" to offset.coerceAtLeast(0),
                ),
                "searchResult3",
            )
        } catch (e: SubsonicApiException) {
            if (e.code == SubsonicApiException.MISSING_PARAMETER || e.code == SubsonicApiException.GENERIC) {
                throw ProviderException.Unsupported("This server cannot list all tracks")
            }
            throw e
        }
        val tracks = result?.song?.let(mapper::tracks).orEmpty()
        if (tracks.isEmpty() && offset == 0) {
            // Empty first page: either the library is empty or the server ignores empty queries.
            val probe = api.getOrNull<SongListDto>("getRandomSongs", mapOf("size" to 1), "randomSongs")?.song.orEmpty()
            if (probe.isNotEmpty()) throw ProviderException.Unsupported("This server cannot list all tracks")
        }
        tracks
    }

    override suspend fun getTrack(id: String): Track = subsonicCall {
        mapper.track(api.get<ChildDto>("getSong", mapOf("id" to id), "song"))
    }

    override suspend fun getRandomTracks(size: Int, genre: String?, fromYear: Int?, toYear: Int?): List<Track> = subsonicCall {
        val params = mapOf("size" to size.coerceIn(1, MAX_PAGE), "genre" to genre, "fromYear" to fromYear, "toYear" to toYear)
        api.getOrNull<SongListDto>("getRandomSongs", params, "randomSongs")?.song?.let(mapper::tracks).orEmpty()
    }

    override suspend fun getTracksByGenre(genre: String, size: Int, offset: Int): List<Track> = subsonicCall {
        val params = mapOf("genre" to genre, "count" to size.coerceIn(1, MAX_PAGE), "offset" to offset.coerceAtLeast(0))
        api.getOrNull<SongListDto>("getSongsByGenre", params, "songsByGenre")?.song?.let(mapper::tracks).orEmpty()
    }

    override suspend fun getSimilarTracks(trackId: String, count: Int): List<Track> = subsonicCall {
        api.getOrNull<SongListDto>("getSimilarSongs2", mapOf("id" to trackId, "count" to count), "similarSongs2")
            ?.song?.let(mapper::tracks).orEmpty()
    }

    override suspend fun getTopTracks(artist: Artist, count: Int): List<Track> = subsonicCall {
        api.getOrNull<SongListDto>("getTopSongs", mapOf("artist" to artist.name, "count" to count), "topSongs")
            ?.song?.let(mapper::tracks).orEmpty()
    }

    // ------------------------------------------------------------- playlists

    override suspend fun getPlaylists(): List<Playlist> = subsonicCall {
        api.getOrNull<PlaylistsDto>("getPlaylists", key = "playlists")?.playlist?.map(mapper::playlist).orEmpty()
    }

    override suspend fun getPlaylist(id: String): PlaylistDetail = subsonicCall {
        val dto = api.get<PlaylistDto>("getPlaylist", mapOf("id" to id), "playlist")
        PlaylistDetail(playlist = mapper.playlist(dto), tracks = mapper.tracks(dto.entry))
    }

    override suspend fun createPlaylist(name: String, trackIds: List<String>): Playlist = subsonicCall {
        val created = api.getOrNull<PlaylistDto>("createPlaylist", mapOf("name" to name, "songId" to trackIds), "playlist")
        if (created != null) return@subsonicCall mapper.playlist(created)
        // Pre-1.14 servers answer with an empty envelope: find the newest playlist with that name.
        getPlaylists().filter { it.name == name }.maxByOrNull { it.updatedAt }
            ?: throw ProviderException.Unknown("Playlist was created but could not be read back")
    }

    override suspend fun updatePlaylist(
        id: String,
        name: String?,
        description: String?,
        isPublic: Boolean?,
        addTrackIds: List<String>,
        removeIndices: List<Int>,
    ): Unit = subsonicCall {
        // Removal indices refer to the playlist as it is now, so apply removals before additions.
        if (removeIndices.isNotEmpty()) {
            api.request("updatePlaylist", mapOf("playlistId" to id, "songIndexToRemove" to removeIndices.sortedDescending()))
        }
        val params = mutableMapOf<String, Any?>("playlistId" to id)
        name?.let { params["name"] = it }
        description?.let { params["comment"] = it }
        isPublic?.let { params["public"] = it }
        if (addTrackIds.isNotEmpty()) params["songIdToAdd"] = addTrackIds
        if (params.size > 1) api.request("updatePlaylist", params)
    }

    override suspend fun deletePlaylist(id: String): Unit = subsonicCall {
        api.request("deletePlaylist", mapOf("id" to id))
    }

    // ------------------------------------------------ genres / stars / search

    override suspend fun getGenres(): List<Genre> = subsonicCall {
        api.getOrNull<GenresDto>("getGenres", key = "genres")?.genre?.map(mapper::genre)
            ?.filter { it.name.isNotBlank() }?.sortedBy { it.name.lowercase() }.orEmpty()
    }

    override suspend fun getStarred(): StarredContent = subsonicCall {
        val dto = api.getOrNull<StarredDto>("getStarred2", key = "starred2") ?: StarredDto()
        StarredContent(
            artists = dto.artist.map(mapper::artist),
            albums = dto.album.map(mapper::album),
            tracks = mapper.tracks(dto.song),
        )
    }

    override suspend fun setStarred(id: String, kind: ItemKind, starred: Boolean): Unit = subsonicCall {
        val param = when (kind) {
            ItemKind.TRACK -> "id"
            ItemKind.ALBUM -> "albumId"
            ItemKind.ARTIST -> "artistId"
            ItemKind.PLAYLIST -> throw ProviderException.Unsupported("Playlists cannot be starred on Subsonic servers")
        }
        api.request(if (starred) "star" else "unstar", mapOf(param to id))
    }

    override suspend fun setRating(id: String, kind: ItemKind, rating: Int): Unit = subsonicCall {
        if (kind == ItemKind.PLAYLIST) throw ProviderException.Unsupported("Playlists cannot be rated on Subsonic servers")
        api.request("setRating", mapOf("id" to id, "rating" to rating.coerceIn(0, 5)))
    }

    override suspend fun search(query: String, artistLimit: Int, albumLimit: Int, trackLimit: Int): SearchResults = subsonicCall {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@subsonicCall SearchResults()
        val dto = api.getOrNull<SearchResultDto>(
            "search3",
            mapOf(
                "query" to trimmed,
                "artistCount" to artistLimit,
                "albumCount" to albumLimit,
                "songCount" to trackLimit,
            ),
            "searchResult3",
        ) ?: SearchResultDto()
        SearchResults(
            artists = dto.artist.map(mapper::artist),
            albums = dto.album.map(mapper::album),
            tracks = mapper.tracks(dto.song),
        )
    }

    // ---------------------------------------------------------------- extras

    override suspend fun getLyrics(track: Track): Lyrics? = subsonicCall {
        if (EXT_SONG_LYRICS in loadExtensions()) {
            val list = optional { api.getOrNull<LyricsListDto>("getLyricsBySongId", mapOf("id" to track.id), "lyricsList") }
            val structured = list?.structuredLyrics
            if (!structured.isNullOrEmpty()) {
                val best = structured.firstOrNull { it.synced } ?: structured.first()
                mapper.lyrics(best)?.let { return@subsonicCall it }
            }
        }
        val artist = track.artistName ?: track.albumArtistName ?: return@subsonicCall null
        val plain = optional { api.getOrNull<LyricsDto>("getLyrics", mapOf("artist" to artist, "title" to track.title), "lyrics") }
        plain?.let(mapper::lyrics)
    }

    override suspend fun reportNowPlaying(trackId: String): Unit = subsonicCall {
        api.request("scrobble", mapOf("id" to trackId, "submission" to false))
    }

    override suspend fun reportPlayed(trackId: String, playedAtEpochMs: Long): Unit = subsonicCall {
        api.request("scrobble", mapOf("id" to trackId, "time" to playedAtEpochMs, "submission" to true))
    }

    // ------------------------------------------------------------------ URLs

    /**
     * Subsonic can only express a target format and a bitrate cap. Sample-rate and bit-depth caps
     * in a FLAC profile are therefore ignored (the server's own transcoding rule for `flac`
     * decides), and an ORIGINAL profile with a bitrate cap keeps the server's default format.
     */
    override fun streamRequest(track: Track, profile: TranscodeProfile): StreamRequest {
        // Never ask for an estimated Content-Length on transcoded streams: the real output is
        // almost never the estimated size, and a shorter body ends the connection early, which the
        // player reports as an error at the end of every track. Raw files have an exact length.
        val params = mutableMapOf<String, Any?>("id" to track.id)
        val transcoded: Boolean
        when {
            profile.isPassthrough -> {
                params["format"] = "raw"
                params["estimateContentLength"] = true
                transcoded = false
            }
            profile.codec == AudioCodec.ORIGINAL -> {
                // Bitrate cap without a codec choice: let the server pick its default transcoding.
                params["maxBitRate"] = profile.maxBitrateKbps
                transcoded = true
            }
            else -> {
                params["format"] = SubsonicMapper.formatParam(profile.codec)
                if (profile.maxBitrateKbps > 0) params["maxBitRate"] = profile.maxBitrateKbps
                transcoded = true
            }
        }
        val mime = if (transcoded && profile.codec != AudioCodec.ORIGINAL) {
            SubsonicMapper.mimeType(profile.codec, track.mimeType)
        } else {
            track.mimeType
        }
        return StreamRequest(
            url = api.urls.buildString("stream", params),
            mimeType = mime,
            isTranscoded = transcoded,
        )
    }

    override fun downloadRequest(track: Track): StreamRequest = StreamRequest(
        url = api.urls.buildString("download", mapOf("id" to track.id)),
        mimeType = track.mimeType,
        isTranscoded = false,
    )

    override fun artworkUrl(artworkId: String?, sizePx: Int): String? = mapper.artworkUrl(artworkId, sizePx)

    // --------------------------------------------------------------- helpers

    /** Like runCatching, but lets cancellation propagate. */
    private suspend inline fun <T> optional(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        const val MAX_PAGE = 500
        const val EXT_SONG_LYRICS = "songLyrics"

        val BASE_CAPABILITIES: Set<Capability> = setOf(
            Capability.LYRICS,
            Capability.RATINGS,
            Capability.FAVORITES,
            Capability.PLAYLIST_EDIT,
            Capability.SCROBBLE,
            Capability.SIMILAR_ARTISTS,
            Capability.TOP_TRACKS,
            Capability.GENRES,
            Capability.RANDOM_TRACKS,
            Capability.TRANSCODE_FORMAT_SELECTION,
            Capability.DOWNLOAD_ORIGINAL,
            Capability.FULL_TRACK_LISTING,
        )
    }
}
