package app.polyphony.provider.jellyfin

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
import app.polyphony.core.domain.transcode.TranscodeProfile
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.artistApi
import org.jellyfin.sdk.api.client.extensions.audioApi
import org.jellyfin.sdk.api.client.extensions.genreApi
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.instantMixApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.lyricApi
import org.jellyfin.sdk.api.client.extensions.playlistApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CreatePlaylistDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.UpdatePlaylistDto
import org.jellyfin.sdk.model.serializer.toUUID
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [MediaProvider] for Jellyfin servers, built on the official Kotlin SDK (generated against the
 * current server API; the endpoints used here exist on 10.9+).
 *
 * Notes:
 * - Jellyfin only has like / dislike, so [Capability.RATINGS] is not advertised. [setRating]
 *   still maps 3–5 → like, 1–2 → dislike, 0 → clear, so callers that ignore capabilities work.
 * - Playlist descriptions cannot be edited through the API and are ignored by [updatePlaylist].
 * - Stream and download URLs carry the access token as a query parameter so remote receivers
 *   (Cast) can fetch them without headers; the `Authorization` header is also provided for the
 *   local player.
 */
@Suppress("DEPRECATION") // /Artists endpoints are deprecated in server 12 but exist on every released server; see albumArtistsPage().
internal class JellyfinMediaProvider(
    override val server: Server,
    private val api: ApiClient,
) : MediaProvider {

    private val userId: UUID? = server.userId?.toUUIDOrNull()
    private val mapper = JellyfinMapper(server.id) { itemId, size -> artworkUrl(itemId, size) }

    override val capabilities: Set<Capability> = setOf(
        Capability.LYRICS,
        Capability.SYNCED_LYRICS,
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

    // ---------------------------------------------------------------- server

    override suspend fun ping(): ServerInfo = jellyfinCall {
        val info = api.systemApi.getPublicSystemInfo().content
        ServerInfo(
            productName = info.productName ?: "Jellyfin",
            version = info.version ?: "unknown",
            apiVersion = info.version,
        )
    }

    // ---------------------------------------------------------------- artists

    override suspend fun getArtists(): List<Artist> = jellyfinCall {
        fetchAll { start, limit -> albumArtistsPage(start = start, limit = limit) }.map(mapper::artist)
    }

    /**
     * Album artists via `/Artists/AlbumArtists`. That controller is deprecated in Jellyfin 12, so
     * if a server answers 404 we fall back to `/Items?includeItemTypes=MusicArtist`, which lists
     * every artist (including contributing-only ones).
     */
    private suspend fun albumArtistsPage(
        start: Int? = null,
        limit: Int? = null,
        searchTerm: String? = null,
        isFavorite: Boolean? = null,
    ): BaseItemDtoQueryResult = try {
        api.artistApi.getAlbumArtists(
            userId = userId,
            startIndex = start,
            limit = limit,
            searchTerm = searchTerm,
            isFavorite = isFavorite,
            fields = ARTIST_FIELDS,
            sortBy = listOf(ItemSortBy.SORT_NAME),
            sortOrder = listOf(SortOrder.ASCENDING),
            enableUserData = true,
            enableImages = true,
        ).content
    } catch (e: InvalidStatusException) {
        if (e.status != 404) throw e
        api.libraryApi.getItems(
            userId = userId,
            includeItemTypes = listOf(BaseItemKind.MUSIC_ARTIST),
            recursive = true,
            startIndex = start,
            limit = limit,
            searchTerm = searchTerm,
            isFavorite = isFavorite,
            fields = ARTIST_FIELDS,
            sortBy = listOf(ItemSortBy.SORT_NAME),
            sortOrder = listOf(SortOrder.ASCENDING),
            enableUserData = true,
            enableImages = true,
        ).content
    }

    override suspend fun getArtist(id: String): ArtistDetail = jellyfinCall {
        val artistId = id.toUUID()
        coroutineScope {
            val item = async { api.libraryApi.getItem(artistId, userId).content }
            val albums = async {
                api.libraryApi.getItems(
                    userId = userId,
                    albumArtistIds = listOf(artistId),
                    includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
                    recursive = true,
                    fields = ALBUM_FIELDS,
                    sortBy = listOf(ItemSortBy.PRODUCTION_YEAR, ItemSortBy.SORT_NAME),
                    sortOrder = listOf(SortOrder.DESCENDING, SortOrder.ASCENDING),
                    enableUserData = true,
                ).content.items.map(mapper::album)
            }
            val topTracks = async { runCatching { topTracksFor(artistId, 10) }.getOrDefault(emptyList()) }
            val similar = async {
                runCatching {
                    api.libraryApi.getSimilarArtists(itemId = artistId, userId = userId, limit = 12, fields = ARTIST_FIELDS)
                        .content.items.map(mapper::artist)
                }.getOrDefault(emptyList())
            }
            ArtistDetail(
                artist = mapper.artist(item.await()),
                albums = albums.await(),
                topTracks = topTracks.await(),
                similarArtists = similar.await(),
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
    ): List<Album> = jellyfinCall {
        val (sortBy, sortOrder) = when (type) {
            AlbumListType.RECENTLY_ADDED -> listOf(ItemSortBy.DATE_CREATED, ItemSortBy.SORT_NAME) to listOf(SortOrder.DESCENDING, SortOrder.ASCENDING)
            AlbumListType.RECENTLY_PLAYED -> listOf(ItemSortBy.DATE_PLAYED, ItemSortBy.SORT_NAME) to listOf(SortOrder.DESCENDING, SortOrder.ASCENDING)
            AlbumListType.MOST_PLAYED -> listOf(ItemSortBy.PLAY_COUNT, ItemSortBy.SORT_NAME) to listOf(SortOrder.DESCENDING, SortOrder.ASCENDING)
            AlbumListType.RANDOM -> listOf(ItemSortBy.RANDOM) to listOf(SortOrder.ASCENDING)
            AlbumListType.STARRED -> listOf(ItemSortBy.SORT_NAME) to listOf(SortOrder.ASCENDING)
            AlbumListType.HIGHEST_RATED -> listOf(ItemSortBy.COMMUNITY_RATING, ItemSortBy.SORT_NAME) to listOf(SortOrder.DESCENDING, SortOrder.ASCENDING)
            AlbumListType.ALPHABETICAL_BY_NAME -> listOf(ItemSortBy.SORT_NAME) to listOf(SortOrder.ASCENDING)
            AlbumListType.ALPHABETICAL_BY_ARTIST -> listOf(ItemSortBy.ALBUM_ARTIST, ItemSortBy.SORT_NAME) to listOf(SortOrder.ASCENDING, SortOrder.ASCENDING)
            AlbumListType.BY_YEAR -> listOf(ItemSortBy.PRODUCTION_YEAR, ItemSortBy.SORT_NAME) to listOf(SortOrder.ASCENDING, SortOrder.ASCENDING)
            AlbumListType.BY_GENRE -> listOf(ItemSortBy.SORT_NAME) to listOf(SortOrder.ASCENDING)
        }
        val filters = when (type) {
            AlbumListType.STARRED -> listOf(ItemFilter.IS_FAVORITE)
            AlbumListType.RECENTLY_PLAYED, AlbumListType.MOST_PLAYED -> listOf(ItemFilter.IS_PLAYED)
            else -> emptyList()
        }
        val years = yearRange(fromYear, toYear, type == AlbumListType.BY_YEAR)
        api.libraryApi.getItems(
            userId = userId,
            includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
            recursive = true,
            startIndex = offset,
            limit = size,
            sortBy = sortBy,
            sortOrder = sortOrder,
            filters = filters,
            genres = listOfNotNull(genre?.takeIf { type == AlbumListType.BY_GENRE || genre.isNotBlank() }),
            years = years,
            fields = ALBUM_FIELDS,
            enableUserData = true,
            enableTotalRecordCount = false,
        ).content.items.map(mapper::album)
    }

    override suspend fun getAlbum(id: String): AlbumDetail = jellyfinCall {
        val albumId = id.toUUID()
        coroutineScope {
            val item = async { api.libraryApi.getItem(albumId, userId).content }
            val tracks = async { albumTracks(albumId) }
            AlbumDetail(album = mapper.album(item.await()), tracks = tracks.await())
        }
    }

    private suspend fun albumTracks(albumId: UUID): List<Track> = fetchAll { start, limit ->
        api.libraryApi.getItems(
            userId = userId,
            parentId = albumId,
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            recursive = true,
            startIndex = start,
            limit = limit,
            sortBy = listOf(ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME),
            sortOrder = listOf(SortOrder.ASCENDING),
            fields = TRACK_FIELDS,
            enableUserData = true,
        ).content
    }.map(mapper::track)

    // ---------------------------------------------------------------- tracks

    override suspend fun getAllTracks(size: Int, offset: Int): List<Track> = jellyfinCall {
        api.libraryApi.getItems(
            userId = userId,
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            recursive = true,
            startIndex = offset,
            limit = size,
            sortBy = listOf(ItemSortBy.ALBUM_ARTIST, ItemSortBy.ALBUM, ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME),
            sortOrder = listOf(SortOrder.ASCENDING),
            fields = TRACK_FIELDS,
            enableUserData = true,
            enableTotalRecordCount = false,
        ).content.items.map(mapper::track)
    }

    override suspend fun getTrack(id: String): Track = jellyfinCall {
        val dto = api.libraryApi.getItem(id.toUUID(), userId).content
        if (dto.type != BaseItemKind.AUDIO) throw ProviderException.NotFound("Item $id is not a track")
        mapper.track(dto)
    }

    override suspend fun getRandomTracks(size: Int, genre: String?, fromYear: Int?, toYear: Int?): List<Track> = jellyfinCall {
        api.libraryApi.getItems(
            userId = userId,
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            recursive = true,
            limit = size,
            sortBy = listOf(ItemSortBy.RANDOM),
            genres = listOfNotNull(genre?.takeIf { it.isNotBlank() }),
            years = yearRange(fromYear, toYear, false),
            fields = TRACK_FIELDS,
            enableUserData = true,
            enableTotalRecordCount = false,
        ).content.items.map(mapper::track)
    }

    override suspend fun getTracksByGenre(genre: String, size: Int, offset: Int): List<Track> = jellyfinCall {
        api.libraryApi.getItems(
            userId = userId,
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            recursive = true,
            genres = listOf(genre),
            startIndex = offset,
            limit = size,
            sortBy = listOf(ItemSortBy.ALBUM_ARTIST, ItemSortBy.ALBUM, ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER),
            sortOrder = listOf(SortOrder.ASCENDING),
            fields = TRACK_FIELDS,
            enableUserData = true,
            enableTotalRecordCount = false,
        ).content.items.map(mapper::track)
    }

    override suspend fun getSimilarTracks(trackId: String, count: Int): List<Track> = jellyfinCall {
        api.instantMixApi.getInstantMixFromSong(
            itemId = trackId.toUUID(),
            userId = userId,
            limit = count,
            fields = TRACK_FIELDS,
            enableUserData = true,
        ).content.items.filter { it.type == BaseItemKind.AUDIO }.map(mapper::track)
    }

    override suspend fun getTopTracks(artist: Artist, count: Int): List<Track> = jellyfinCall {
        topTracksFor(artist.id.toUUID(), count)
    }

    private suspend fun topTracksFor(artistId: UUID, count: Int): List<Track> =
        api.libraryApi.getItems(
            userId = userId,
            artistIds = listOf(artistId),
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            recursive = true,
            limit = count,
            sortBy = listOf(ItemSortBy.PLAY_COUNT, ItemSortBy.SORT_NAME),
            sortOrder = listOf(SortOrder.DESCENDING, SortOrder.ASCENDING),
            fields = TRACK_FIELDS,
            enableUserData = true,
            enableTotalRecordCount = false,
        ).content.items.map(mapper::track)

    // ---------------------------------------------------------------- playlists

    override suspend fun getPlaylists(): List<Playlist> = jellyfinCall {
        fetchAll { start, limit ->
            api.libraryApi.getItems(
                userId = userId,
                includeItemTypes = listOf(BaseItemKind.PLAYLIST),
                recursive = true,
                startIndex = start,
                limit = limit,
                sortBy = listOf(ItemSortBy.SORT_NAME),
                sortOrder = listOf(SortOrder.ASCENDING),
                fields = PLAYLIST_FIELDS,
                enableUserData = true,
            ).content
        }.filter { it.mediaType == MediaType.AUDIO || it.mediaType == MediaType.UNKNOWN }
            .map { mapper.playlist(it) }
    }

    override suspend fun getPlaylist(id: String): PlaylistDetail = jellyfinCall {
        val playlistId = id.toUUID()
        coroutineScope {
            val item = async { api.libraryApi.getItem(playlistId, userId).content }
            val isPublic = async { runCatching { api.playlistApi.getPlaylist(playlistId).content.openAccess }.getOrDefault(false) }
            val tracks = async { playlistItems(playlistId).map(mapper::track) }
            PlaylistDetail(playlist = mapper.playlist(item.await(), isPublic.await()), tracks = tracks.await())
        }
    }

    private suspend fun playlistItems(playlistId: UUID): List<BaseItemDto> = fetchAll { start, limit ->
        api.playlistApi.getPlaylistItems(
            playlistId = playlistId,
            userId = userId,
            startIndex = start,
            limit = limit,
            fields = TRACK_FIELDS,
            enableUserData = true,
            enableImages = true,
        ).content
    }

    override suspend fun createPlaylist(name: String, trackIds: List<String>): Playlist = jellyfinCall {
        val result = api.playlistApi.createPlaylist(
            CreatePlaylistDto(
                name = name,
                ids = trackIds.map { it.toUUID() },
                userId = userId,
                mediaType = MediaType.AUDIO,
                users = emptyList(),
                isPublic = false,
            ),
        ).content
        val created = api.libraryApi.getItem(result.id.toUUID(), userId).content
        mapper.playlist(created)
    }

    override suspend fun updatePlaylist(
        id: String,
        name: String?,
        description: String?,
        isPublic: Boolean?,
        addTrackIds: List<String>,
        removeIndices: List<Int>,
    ): Unit = jellyfinCall {
        val playlistId = id.toUUID()
        if (name != null || isPublic != null) {
            api.playlistApi.updatePlaylist(playlistId, UpdatePlaylistDto(name = name, isPublic = isPublic))
        }
        if (removeIndices.isNotEmpty()) {
            val items = playlistItems(playlistId)
            val entryIds = removeIndices.mapNotNull { index -> items.getOrNull(index)?.playlistItemId }
            if (entryIds.isNotEmpty()) {
                api.playlistApi.removeItemFromPlaylist(playlistId = id, entryIds = entryIds)
            }
        }
        if (addTrackIds.isNotEmpty()) {
            api.playlistApi.addItemToPlaylist(playlistId = playlistId, ids = addTrackIds.map { it.toUUID() }, userId = userId)
        }
    }

    override suspend fun deletePlaylist(id: String): Unit = jellyfinCall {
        api.libraryApi.deleteItem(id.toUUID())
    }

    // ---------------------------------------------------------------- genres / favourites / ratings / search

    override suspend fun getGenres(): List<Genre> = jellyfinCall {
        fetchAll { start, limit ->
            api.genreApi.getGenres(
                userId = userId,
                startIndex = start,
                limit = limit,
                includeItemTypes = listOf(BaseItemKind.AUDIO, BaseItemKind.MUSIC_ALBUM),
                fields = listOf(ItemFields.ITEM_COUNTS),
                sortBy = listOf(ItemSortBy.SORT_NAME),
                sortOrder = listOf(SortOrder.ASCENDING),
                enableImages = false,
            ).content
        }.map(mapper::genre).filter { it.name.isNotBlank() }
    }

    override suspend fun getStarred(): StarredContent = jellyfinCall {
        coroutineScope {
            val artists = async {
                fetchAll { start, limit -> albumArtistsPage(start = start, limit = limit, isFavorite = true) }.map(mapper::artist)
            }
            val albums = async { favorites(BaseItemKind.MUSIC_ALBUM, ALBUM_FIELDS).map(mapper::album) }
            val tracks = async { favorites(BaseItemKind.AUDIO, TRACK_FIELDS).map(mapper::track) }
            StarredContent(artists = artists.await(), albums = albums.await(), tracks = tracks.await())
        }
    }

    private suspend fun favorites(kind: BaseItemKind, fields: List<ItemFields>): List<BaseItemDto> = fetchAll { start, limit ->
        api.libraryApi.getItems(
            userId = userId,
            includeItemTypes = listOf(kind),
            recursive = true,
            filters = listOf(ItemFilter.IS_FAVORITE),
            startIndex = start,
            limit = limit,
            sortBy = listOf(ItemSortBy.SORT_NAME),
            sortOrder = listOf(SortOrder.ASCENDING),
            fields = fields,
            enableUserData = true,
        ).content
    }

    override suspend fun setStarred(id: String, kind: ItemKind, starred: Boolean): Unit = jellyfinCall {
        val itemId = id.toUUID()
        if (starred) api.userDataApi.markFavoriteItem(itemId, userId)
        else api.userDataApi.unmarkFavoriteItem(itemId, userId)
    }

    override suspend fun setRating(id: String, kind: ItemKind, rating: Int): Unit = jellyfinCall {
        val itemId = id.toUUID()
        when {
            rating <= 0 -> api.userDataApi.deleteUserItemRating(itemId, userId)
            rating >= 3 -> api.userDataApi.updateUserItemRating(itemId, userId, likes = true)
            else -> api.userDataApi.updateUserItemRating(itemId, userId, likes = false)
        }
    }

    override suspend fun search(query: String, artistLimit: Int, albumLimit: Int, trackLimit: Int): SearchResults = jellyfinCall {
        val term = query.trim()
        if (term.isEmpty()) return@jellyfinCall SearchResults()
        coroutineScope {
            val artists = async {
                albumArtistsPage(limit = artistLimit, searchTerm = term).items.map(mapper::artist)
            }
            val albums = async { searchItems(term, BaseItemKind.MUSIC_ALBUM, albumLimit, ALBUM_FIELDS).map(mapper::album) }
            val tracks = async { searchItems(term, BaseItemKind.AUDIO, trackLimit, TRACK_FIELDS).map(mapper::track) }
            SearchResults(artists = artists.await(), albums = albums.await(), tracks = tracks.await())
        }
    }

    private suspend fun searchItems(term: String, kind: BaseItemKind, limit: Int, fields: List<ItemFields>): List<BaseItemDto> =
        api.libraryApi.getItems(
            userId = userId,
            searchTerm = term,
            includeItemTypes = listOf(kind),
            recursive = true,
            limit = limit,
            fields = fields,
            enableUserData = true,
            enableTotalRecordCount = false,
        ).content.items

    // ---------------------------------------------------------------- extras

    override suspend fun getLyrics(track: Track): Lyrics? = try {
        jellyfinCall { mapper.lyrics(api.lyricApi.getLyrics(track.id.toUUID()).content) }
    } catch (e: ProviderException.NotFound) {
        null
    }

    override suspend fun reportNowPlaying(trackId: String): Unit = jellyfinCall {
        api.sessionApi.reportPlaybackStart(
            PlaybackStartInfo(
                canSeek = true,
                itemId = trackId.toUUID(),
                isPaused = false,
                isMuted = false,
                positionTicks = 0L,
                playMethod = PlayMethod.DIRECT_STREAM,
                repeatMode = RepeatMode.REPEAT_NONE,
                playbackOrder = PlaybackOrder.DEFAULT,
            ),
        )
    }

    override suspend fun reportPlayed(trackId: String, playedAtEpochMs: Long): Unit = jellyfinCall {
        val playedAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(playedAtEpochMs), ZoneId.systemDefault())
        api.userDataApi.markPlayedItem(itemId = trackId.toUUID(), userId = userId, datePlayed = playedAt)
    }

    // ---------------------------------------------------------------- URLs

    override fun streamRequest(track: Track, profile: TranscodeProfile): StreamRequest {
        val params = JellyfinStreamParams.from(profile, track)
        val url = api.audioApi.getUniversalAudioStreamUrl(
            itemId = track.id.toUUID(),
            container = params.containers,
            deviceId = server.deviceId,
            userId = userId,
            audioCodec = params.audioCodec,
            maxStreamingBitrate = params.maxStreamingBitrate,
            audioBitRate = params.audioBitRate,
            transcodingContainer = params.transcodingContainer,
            transcodingProtocol = MediaStreamProtocol.HTTP,
            maxAudioSampleRate = params.maxAudioSampleRate,
            maxAudioBitDepth = params.maxAudioBitDepth,
            enableRedirection = true,
        )
        return StreamRequest(
            url = withAccessToken(url),
            headers = authHeaders(),
            mimeType = params.mimeTypeFor(track),
            isTranscoded = params.likelyTranscoded,
        )
    }

    override fun downloadRequest(track: Track): StreamRequest = StreamRequest(
        url = withAccessToken(api.libraryApi.getDownloadUrl(track.id.toUUID())),
        headers = authHeaders(),
        mimeType = JellyfinStreamParams.directMimeType(track.container) ?: track.mimeType,
        isTranscoded = false,
    )

    override fun artworkUrl(artworkId: String?, sizePx: Int): String? {
        val itemId = artworkId?.toUUIDOrNull() ?: return null
        return api.imageApi.getItemImageUrl(
            itemId = itemId,
            imageType = ImageType.PRIMARY,
            maxWidth = sizePx,
            maxHeight = sizePx,
            quality = 90,
        )
    }

    private fun withAccessToken(url: String): String {
        val token = api.accessToken ?: return url
        val parsed = url.toHttpUrlOrNull() ?: return url
        return parsed.newBuilder()
            .setQueryParameter(ApiClient.QUERY_ACCESS_TOKEN, token)
            .setQueryParameter(LEGACY_QUERY_ACCESS_TOKEN, token)
            .build()
            .toString()
    }

    private fun authHeaders(): Map<String, String> = mapOf(
        "Authorization" to AuthorizationHeaderBuilder.buildHeader(
            clientName = api.clientInfo.name,
            clientVersion = api.clientInfo.version,
            deviceId = api.deviceInfo.id,
            deviceName = api.deviceInfo.name,
            accessToken = api.accessToken,
        ),
    )

    // ---------------------------------------------------------------- helpers

    /** Walks a paged endpoint until every item has been fetched. */
    private suspend fun fetchAll(
        pageSize: Int = PAGE_SIZE,
        fetch: suspend (start: Int, limit: Int) -> BaseItemDtoQueryResult,
    ): List<BaseItemDto> {
        val out = ArrayList<BaseItemDto>()
        var start = 0
        while (true) {
            val page = fetch(start, pageSize)
            out += page.items
            start += page.items.size
            if (page.items.isEmpty() || start >= page.totalRecordCount) break
        }
        return out
    }

    private fun yearRange(fromYear: Int?, toYear: Int?, required: Boolean): List<Int> {
        val from = fromYear ?: if (required) toYear else null
        val to = toYear ?: if (required) fromYear else null
        if (from == null || to == null) return emptyList()
        val lo = minOf(from, to)
        val hi = maxOf(from, to).coerceAtMost(lo + MAX_YEAR_SPAN)
        return (lo..hi).toList()
    }

    private companion object {
        const val PAGE_SIZE = 500
        const val MAX_YEAR_SPAN = 300
        const val LEGACY_QUERY_ACCESS_TOKEN = "api_key"

        val TRACK_FIELDS = listOf(
            ItemFields.MEDIA_SOURCES, ItemFields.GENRES, ItemFields.DATE_CREATED, ItemFields.PATH,
            ItemFields.PROVIDER_IDS, ItemFields.PARENT_ID, ItemFields.SORT_NAME,
        )
        val ALBUM_FIELDS = listOf(
            ItemFields.SORT_NAME, ItemFields.DATE_CREATED, ItemFields.GENRES, ItemFields.PROVIDER_IDS,
            ItemFields.CHILD_COUNT, ItemFields.ITEM_COUNTS, ItemFields.CUMULATIVE_RUN_TIME_TICKS,
        )
        val ARTIST_FIELDS = listOf(
            ItemFields.SORT_NAME, ItemFields.PROVIDER_IDS, ItemFields.ITEM_COUNTS, ItemFields.CHILD_COUNT,
            ItemFields.OVERVIEW,
        )
        val PLAYLIST_FIELDS = listOf(
            ItemFields.CHILD_COUNT, ItemFields.DATE_CREATED, ItemFields.OVERVIEW,
            ItemFields.CUMULATIVE_RUN_TIME_TICKS, ItemFields.DATE_LAST_MEDIA_ADDED,
        )
    }
}
