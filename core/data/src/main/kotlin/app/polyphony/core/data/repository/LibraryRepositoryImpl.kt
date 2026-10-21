package app.polyphony.core.data.repository

import androidx.room.withTransaction
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.filter
import androidx.paging.map
import app.polyphony.core.data.db.PolyphonyDatabase
import app.polyphony.core.data.db.SearchQueries
import app.polyphony.core.data.db.SortQueries
import app.polyphony.core.data.db.entity.AlbumEntity
import app.polyphony.core.data.db.entity.ArtistEntity
import app.polyphony.core.data.db.entity.GenreEntity
import app.polyphony.core.data.db.entity.PlayHistoryEntity
import app.polyphony.core.data.db.entity.PlaylistEntity
import app.polyphony.core.data.db.entity.PlaylistTrackEntity
import app.polyphony.core.data.db.entity.TrackEntity
import app.polyphony.core.data.di.ApplicationScope
import app.polyphony.core.data.di.IoDispatcher
import app.polyphony.core.data.mappers.toDomain
import app.polyphony.core.data.mappers.toEntity
import app.polyphony.core.data.offline.OfflineFilter
import app.polyphony.core.data.offline.OfflineIndex
import app.polyphony.core.data.search.FtsQuery
import app.polyphony.core.data.sync.SyncGenerationTracker
import app.polyphony.core.data.util.attempt
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.AlbumSort
import app.polyphony.core.domain.model.Artist
import app.polyphony.core.domain.model.ArtistSort
import app.polyphony.core.domain.model.Genre
import app.polyphony.core.domain.model.ItemKind
import app.polyphony.core.domain.model.Lyrics
import app.polyphony.core.domain.model.Playlist
import app.polyphony.core.domain.model.SearchResults
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.model.TrackSort
import app.polyphony.core.domain.model.LetterIndex
import app.polyphony.core.domain.model.LibraryFilter
import app.polyphony.core.domain.provider.MediaProvider
import app.polyphony.core.domain.provider.ProviderException
import app.polyphony.core.domain.repository.DownloadRepository
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.ProviderRegistry
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.core.domain.repository.SettingsRepository
import dagger.Lazy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-first implementation. Every read is a Flow scoped to the active server; mutations are
 * applied locally first and reverted if the server rejects them.
 */
@Singleton
class LibraryRepositoryImpl @Inject constructor(
    private val db: PolyphonyDatabase,
    private val serverRepository: ServerRepository,
    private val providerRegistry: ProviderRegistry,
    private val generations: SyncGenerationTracker,
    private val settingsRepository: SettingsRepository,
    /** Lazy: the downloads engine depends on this repository, so the graph has a cycle to break. */
    private val downloadRepository: Lazy<DownloadRepository>,
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope appScope: CoroutineScope,
) : LibraryRepository {

    private val artistDao = db.artistDao()
    private val albumDao = db.albumDao()
    private val trackDao = db.trackDao()
    private val playlistDao = db.playlistDao()
    private val genreDao = db.genreDao()
    private val historyDao = db.playHistoryDao()

    private val activeServerId: Flow<String?> = serverRepository.activeServer
        .map { it?.id }
        .distinctUntilChanged()

    /**
     * `null` while offline mode is off (no extra work at all). When on, the set of downloaded
     * tracks and the albums/artists/playlists/genres they belong to, rebuilt when downloads,
     * playlist membership or the active server change. Shared so every list flow reuses it.
     */
    private val offlineIndex: Flow<OfflineIndex?> = settingsRepository.settings
        .map { it.downloads.offlineMode }
        .distinctUntilChanged()
        .flatMapLatest { on ->
            if (!on) {
                flowOf(null)
            } else {
                activeServerId.flatMapLatest { sid ->
                    if (sid == null) {
                        flowOf(OfflineIndex.EMPTY)
                    } else {
                        combine(
                            downloadRepository.get().downloadedTrackIds(),
                            playlistDao.observeMembershipCount(sid),
                        ) { ids, _ -> ids }
                            .mapLatest { ids -> buildOfflineIndex(sid, ids) }
                    }
                }
            }
        }
        .distinctUntilChanged()
        .shareIn(appScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /** The same index whether or not offline mode is on: the Downloaded search scope. */
    private val downloadedIndex: Flow<OfflineIndex> = activeServerId
        .flatMapLatest { sid ->
            if (sid == null) {
                flowOf(OfflineIndex.EMPTY)
            } else {
                combine(
                    downloadRepository.get().downloadedTrackIds(),
                    playlistDao.observeMembershipCount(sid),
                ) { ids, _ -> ids }
                    .mapLatest { ids -> buildOfflineIndex(sid, ids) }
            }
        }
        .distinctUntilChanged()
        .shareIn(appScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private suspend fun buildOfflineIndex(serverId: String, trackIds: Set<String>): OfflineIndex {
        if (trackIds.isEmpty()) return OfflineIndex.EMPTY
        val refs = ArrayList<app.polyphony.core.data.offline.OfflineTrackRef>(trackIds.size)
        val playlistIds = HashSet<String>()
        trackIds.chunked(OfflineFilter.IN_CHUNK).forEach { chunk ->
            refs += trackDao.getRefs(serverId, chunk)
            playlistIds += playlistDao.getPlaylistIdsForTracks(serverId, chunk)
        }
        return OfflineIndex.build(trackIds, refs, playlistIds)
    }

    private fun <T> Flow<List<T>>.offline(
        allowed: (OfflineIndex) -> Set<String>,
        key: (T) -> String,
        index: Flow<OfflineIndex?> = offlineIndex,
    ): Flow<List<T>> =
        combine(this, index) { list, i -> OfflineFilter.apply(list, i, allowed, key) }
            .distinctUntilChanged()

    /**
     * The downloads index a list must be narrowed by: every download for a Downloaded filter
     * (offline mode or not), else offline mode's index, which is `null` (no narrowing) while off.
     */
    private fun indexFor(filter: LibraryFilter): Flow<OfflineIndex?> =
        if (filter.downloadedOnly) downloadedIndex else offlineIndex

    private suspend fun offlineMode(): Boolean =
        runCatching { settingsRepository.current().downloads.offlineMode }.getOrDefault(false)

    // ---------------------------------------------------------------- helpers

    private fun <T> scopedList(block: (String) -> Flow<List<T>>): Flow<List<T>> =
        activeServerId
            .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else block(id) }
            .distinctUntilChanged()

    private fun <T : Any> scopedItem(block: (String) -> Flow<T?>): Flow<T?> =
        activeServerId
            .flatMapLatest { id -> if (id == null) flowOf(null) else block(id) }
            .distinctUntilChanged()

    private fun <T, R> Flow<List<T>>.mapEach(transform: (T) -> R): Flow<List<R>> = map { list -> list.map(transform) }

    private suspend fun requireServerId(): String =
        serverRepository.getActive()?.id ?: throw ProviderException.Unknown("No active server")

    private suspend fun provider(): MediaProvider =
        providerRegistry.active() ?: throw ProviderException.Unknown("No active server")

    private suspend fun <T> remote(block: suspend (MediaProvider) -> T): T = withContext(io) { block(provider()) }

    private suspend fun generation(serverId: String): Long = generations.current(serverId)

    private suspend fun cacheTracks(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val gen = generation(tracks.first().serverId)
        attempt { trackDao.upsertAll(tracks.map { it.toEntity(gen) }) }
    }

    // ---------------------------------------------------------------- artists

    override fun artists(sort: ArtistSort, filter: LibraryFilter): Flow<List<Artist>> {
        val f = filter.restrictedTo(ItemKind.ARTIST)
        return scopedList { sid -> artistDao.observeSorted(SortQueries.artists(sid, sort, f)).mapEach(ArtistEntity::toDomain) }
            .offline(OfflineIndex::artistIds, Artist::id, indexFor(f))
    }

    override fun artist(id: String): Flow<Artist?> =
        scopedItem { sid -> artistDao.observe(sid, id).map { it?.toDomain() } }

    override fun starredArtists(): Flow<List<Artist>> =
        scopedList { sid -> artistDao.observeStarred(sid).mapEach(ArtistEntity::toDomain) }.offline(OfflineIndex::artistIds, Artist::id)

    // ---------------------------------------------------------------- albums

    override fun albums(sort: AlbumSort, ascending: Boolean, filter: LibraryFilter): Flow<List<Album>> {
        val f = filter.restrictedTo(ItemKind.ALBUM)
        return scopedList { sid -> albumDao.observeSorted(SortQueries.albums(sid, sort, ascending, f)).mapEach(AlbumEntity::toDomain) }
            .offline(OfflineIndex::albumIds, Album::id, indexFor(f))
    }

    override fun album(id: String): Flow<Album?> =
        scopedItem { sid -> albumDao.observe(sid, id).map { it?.toDomain() } }

    override fun albumsByArtist(artistId: String): Flow<List<Album>> =
        scopedList { sid -> albumDao.observeByArtist(sid, artistId).mapEach(AlbumEntity::toDomain) }.offline(OfflineIndex::albumIds, Album::id)

    override fun albumsByGenre(genre: String): Flow<List<Album>> =
        scopedList { sid -> albumDao.observeByGenre(sid, genre).mapEach(AlbumEntity::toDomain) }.offline(OfflineIndex::albumIds, Album::id)

    override fun starredAlbums(): Flow<List<Album>> =
        scopedList { sid -> albumDao.observeStarred(sid).mapEach(AlbumEntity::toDomain) }.offline(OfflineIndex::albumIds, Album::id)

    override fun recentlyAddedAlbums(limit: Int): Flow<List<Album>> =
        scopedList { sid -> albumDao.observeRecentlyAdded(sid, limit).mapEach(AlbumEntity::toDomain) }.offline(OfflineIndex::albumIds, Album::id)

    override fun recentlyPlayedAlbums(limit: Int): Flow<List<Album>> =
        scopedList { sid -> albumDao.observeRecentlyPlayed(sid, limit).mapEach(AlbumEntity::toDomain) }.offline(OfflineIndex::albumIds, Album::id)

    override fun mostPlayedAlbums(limit: Int): Flow<List<Album>> =
        scopedList { sid -> albumDao.observeMostPlayed(sid, limit).mapEach(AlbumEntity::toDomain) }.offline(OfflineIndex::albumIds, Album::id)

    override fun randomAlbums(limit: Int, seed: Long): Flow<List<Album>> =
        scopedList { sid -> albumDao.observeRandom(sid, limit, seed).mapEach(AlbumEntity::toDomain) }.offline(OfflineIndex::albumIds, Album::id)

    // ---------------------------------------------------------------- tracks

    override fun tracks(sort: TrackSort, ascending: Boolean): Flow<List<Track>> =
        scopedList { sid -> trackDao.observeSorted(SortQueries.tracks(sid, sort, ascending)).mapEach(TrackEntity::toDomain) }.offline(OfflineIndex::trackIds, Track::id)

    override fun pagedTracks(sort: TrackSort, ascending: Boolean, filter: LibraryFilter): Flow<PagingData<Track>> {
        val f = filter.restrictedTo(ItemKind.TRACK)
        return combine(activeServerId, indexFor(f)) { sid, index -> sid to index }
            .distinctUntilChanged()
            .flatMapLatest { (sid, index) ->
                if (sid == null) {
                    flowOf(PagingData.empty())
                } else {
                    // Placeholders give the list its full length up front, so the alphabet rail can
                    // jump anywhere and Room reloads around the target. Narrowed by downloads (offline
                    // mode or a Downloaded filter), rows are filtered after loading, which would leave
                    // blank placeholders, so they are off.
                    val config = PagingConfig(
                        pageSize = TRACK_PAGE,
                        prefetchDistance = TRACK_PAGE,
                        initialLoadSize = TRACK_PAGE * 2,
                        enablePlaceholders = index == null,
                        jumpThreshold = TRACK_PAGE * 3,
                    )
                    Pager(config) { trackDao.pagingSorted(SortQueries.tracks(sid, sort, ascending, f)) }.flow
                        .map { data ->
                            val mapped = data.map { it.toDomain() }
                            if (index == null) mapped else mapped.filter { it.id in index.trackIds }
                        }
                }
            }
    }

    override fun trackCount(filter: LibraryFilter): Flow<Int> {
        val f = filter.restrictedTo(ItemKind.TRACK)
        val columns = f.copy(downloadedOnly = false)
        return combine(activeServerId, indexFor(f)) { sid, index -> sid to index }
            .distinctUntilChanged()
            .flatMapLatest { (sid, index) ->
                when {
                    sid == null -> flowOf(0)
                    index == null -> trackDao.observeCountOf(SortQueries.trackCount(sid, columns))
                    !columns.isActive -> flowOf(index.trackIds.size)
                    // Downloads and column filters together: count the matching rows that are downloaded.
                    else -> trackDao.observeIds(SortQueries.trackIds(sid, columns))
                        .map { ids -> ids.count { it in index.trackIds } }
                        .flowOn(io)
                }
            }
            .distinctUntilChanged()
    }

    override fun trackLetterIndex(sort: TrackSort, ascending: Boolean, filter: LibraryFilter): Flow<LetterIndex> {
        val f = filter.restrictedTo(ItemKind.TRACK)
        return combine(activeServerId, indexFor(f)) { sid, index -> sid to index }
            .distinctUntilChanged()
            .flatMapLatest { (sid, index) ->
                // Narrowed by downloads, the list is a filtered subset whose positions the sort keys cannot predict.
                if (sid == null || index != null) {
                    flowOf(LetterIndex.EMPTY)
                } else {
                    trackDao.observeSortKeys(SortQueries.trackSortKeys(sid, sort, ascending, f))
                        .map { keys -> LetterIndex.of(keys.map { it.k }) }
                        .flowOn(io)
                }
            }
            .distinctUntilChanged()
    }

    override suspend fun tracksSlice(sort: TrackSort, ascending: Boolean, offset: Int, limit: Int, filter: LibraryFilter): List<Track> {
        val sid = serverRepository.getActive()?.id ?: return emptyList()
        val f = filter.restrictedTo(ItemKind.TRACK)
        val rows = trackDao.listSorted(SortQueries.tracksSlice(sid, sort, ascending, limit, offset.coerceAtLeast(0), f))
        return filterDownloads(rows.map(TrackEntity::toDomain), f)
    }

    override suspend fun randomCachedTracks(limit: Int, filter: LibraryFilter): List<Track> {
        val sid = serverRepository.getActive()?.id ?: return emptyList()
        val f = filter.restrictedTo(ItemKind.TRACK)
        if (f.downloadedOnly) {
            // Sampling the whole table and keeping the downloaded rows would come up short; sample the downloads.
            val index = downloadedIndex.first()
            val picks = index.trackIds.shuffled().take(limit * 4)
            val rows = picks.chunked(IN_CHUNK).flatMap { trackDao.getByIds(sid, it) }.map(TrackEntity::toDomain)
            return filterOffline(rows.filter(f::matches)).take(limit)
        }
        val rows = if (f.isActive) trackDao.listSorted(SortQueries.randomTracks(sid, f, limit)) else trackDao.random(sid, limit)
        return filterOffline(rows.map(TrackEntity::toDomain))
    }

    private suspend fun filterOffline(tracks: List<Track>): List<Track> {
        val index = offlineIndex.first() ?: return tracks
        return tracks.filter { it.id in index.trackIds }
    }

    private suspend fun filterDownloads(tracks: List<Track>, filter: LibraryFilter): List<Track> {
        val index = indexFor(filter).first() ?: return tracks
        return tracks.filter { it.id in index.trackIds }
    }

    override fun track(id: String): Flow<Track?> =
        scopedItem { sid -> trackDao.observe(sid, id).map { it?.toDomain() } }

    override fun tracksForAlbum(albumId: String): Flow<List<Track>> =
        scopedList { sid -> trackDao.observeForAlbum(sid, albumId).mapEach(TrackEntity::toDomain) }.offline(OfflineIndex::trackIds, Track::id)

    override fun tracksForArtist(artistId: String): Flow<List<Track>> =
        scopedList { sid -> trackDao.observeForArtist(sid, artistId).mapEach(TrackEntity::toDomain) }.offline(OfflineIndex::trackIds, Track::id)

    override fun tracksByGenre(genre: String): Flow<List<Track>> =
        scopedList { sid -> trackDao.observeByGenre(sid, genre).mapEach(TrackEntity::toDomain) }.offline(OfflineIndex::trackIds, Track::id)

    override fun starredTracks(): Flow<List<Track>> =
        scopedList { sid -> trackDao.observeStarred(sid).mapEach(TrackEntity::toDomain) }.offline(OfflineIndex::trackIds, Track::id)

    override fun recentlyPlayedTracks(limit: Int): Flow<List<Track>> =
        scopedList { sid -> trackDao.observeRecentlyPlayed(sid, limit).mapEach(TrackEntity::toDomain) }.offline(OfflineIndex::trackIds, Track::id)

    override suspend fun getTrack(id: String): Track? {
        val sid = serverRepository.getActive()?.id ?: return null
        trackDao.get(sid, id)?.let { return it.toDomain() }
        if (offlineMode()) return null
        val fetched = attempt { remote { it.getTrack(id) } } ?: return null
        cacheTracks(listOf(fetched))
        return fetched
    }

    override suspend fun getTracks(ids: List<String>): List<Track> {
        if (ids.isEmpty()) return emptyList()
        val sid = serverRepository.getActive()?.id ?: return emptyList()
        val found = HashMap<String, Track>(ids.size)
        ids.distinct().chunked(IN_CHUNK).forEach { chunk ->
            trackDao.getByIds(sid, chunk).forEach { found[it.id] = it.toDomain() }
        }
        val missing = ids.filter { it !in found }.distinct()
        if (missing.isNotEmpty() && !offlineMode()) {
            val provider = providerRegistry.active()
            if (provider != null) {
                val fetched = withContext(io) {
                    coroutineScope {
                        val semaphore = Semaphore(4)
                        missing.map { id -> async { semaphore.withPermit { attempt { provider.getTrack(id) } } } }
                            .awaitAll()
                            .filterNotNull()
                    }
                }
                cacheTracks(fetched)
                fetched.forEach { found[it.id] = it }
            }
        }
        return ids.mapNotNull { found[it] }
    }

    // ---------------------------------------------------------------- playlists

    override fun playlists(filter: LibraryFilter): Flow<List<Playlist>> =
        scopedList { sid -> playlistDao.observeAll(sid).mapEach(PlaylistEntity::toDomain) }
            .offline(OfflineIndex::playlistIds, Playlist::id, indexFor(filter.restrictedTo(ItemKind.PLAYLIST)))

    override fun playlist(id: String): Flow<Playlist?> =
        scopedItem { sid -> playlistDao.observe(sid, id).map { it?.toDomain() } }

    override fun tracksForPlaylist(playlistId: String): Flow<List<Track>> =
        scopedList { sid -> playlistDao.observeTracks(sid, playlistId).mapEach(TrackEntity::toDomain) }.offline(OfflineIndex::trackIds, Track::id)

    // ---------------------------------------------------------------- genres

    override fun genres(): Flow<List<Genre>> =
        scopedList { sid -> genreDao.observeAll(sid).mapEach(GenreEntity::toDomain) }.offline(OfflineIndex::genres, Genre::name)

    override fun yearRange(): Flow<IntRange?> =
        activeServerId
            .flatMapLatest { sid ->
                if (sid == null) {
                    flowOf(null)
                } else {
                    albumDao.observeYearRange(sid).map { r -> if (r.earliest != null && r.latest != null) r.earliest..r.latest else null }
                }
            }
            .distinctUntilChanged()

    // ---------------------------------------------------------------- search

    override fun searchLocal(query: String, limit: Int, filter: LibraryFilter): Flow<SearchResults> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return flowOf(SearchResults())
        // Downloaded-only filters after the query, so look further to keep enough matches.
        @Suppress("NAME_SHADOWING")
        val limit = if (filter.downloadedOnly) limit * 4 else limit
        val filterIndex: Flow<OfflineIndex?> = indexFor(filter)
        val match = FtsQuery.match(trimmed)
        val like = FtsQuery.likePattern(trimmed)
        // A section the filter cannot hold for (a rating filter and playlists, a genre and artists) stays empty.
        fun <T> unless(kind: ItemKind, block: () -> Flow<List<T>>): Flow<List<T>> =
            if (filter.excludesAll(kind)) flowOf(emptyList()) else block()
        return activeServerId.flatMapLatest { sid ->
            if (sid == null) {
                flowOf(SearchResults())
            } else {
                val artists = unless(ItemKind.ARTIST) {
                    withLikeFallback(
                        fts = match?.let { artistDao.observeSorted(SearchQueries.artistsFts(sid, it, filter, limit)) },
                        like = artistDao.observeSorted(SearchQueries.artistsLike(sid, like, filter, limit)),
                    ).mapEach(ArtistEntity::toDomain)
                }
                val albums = unless(ItemKind.ALBUM) {
                    withLikeFallback(
                        fts = match?.let { albumDao.observeSorted(SearchQueries.albumsFts(sid, it, filter, limit)) },
                        like = albumDao.observeSorted(SearchQueries.albumsLike(sid, like, filter, limit)),
                    ).mapEach(AlbumEntity::toDomain)
                }
                val tracks = unless(ItemKind.TRACK) {
                    withLikeFallback(
                        fts = match?.let { trackDao.observeSorted(SearchQueries.tracksFts(sid, it, filter, limit)) },
                        like = trackDao.observeSorted(SearchQueries.tracksLike(sid, like, filter, limit)),
                    ).mapEach(TrackEntity::toDomain)
                }
                combine(artists, albums, tracks, filterIndex) { a, b, c, index ->
                    SearchResults(
                        artists = OfflineFilter.apply(a, index, OfflineIndex::artistIds, Artist::id),
                        albums = OfflineFilter.apply(b, index, OfflineIndex::albumIds, Album::id),
                        tracks = OfflineFilter.apply(c, index, OfflineIndex::trackIds, Track::id),
                    )
                }
            }
        }.distinctUntilChanged()
    }

    /** Prefix FTS first; if it finds nothing (e.g. a mid-word fragment), fall back to LIKE. */
    private fun <T> withLikeFallback(fts: Flow<List<T>>?, like: Flow<List<T>>): Flow<List<T>> =
        fts?.flatMapLatest { results -> if (results.isEmpty()) like else flowOf(results) } ?: like

    override suspend fun searchRemote(query: String): SearchResults {
        if (offlineMode()) return SearchResults()
        return remote { it.search(query) }.also { cacheTracks(it.tracks) }
    }

    // ---------------------------------------------------------------- refresh

    override suspend fun refreshArtist(id: String) {
        if (offlineMode()) return
        val detail = remote { it.getArtist(id) }
        val sid = detail.artist.serverId
        val gen = generation(sid)
        db.withTransaction {
            artistDao.upsert(detail.artist.toEntity(gen))
            if (detail.albums.isNotEmpty()) albumDao.upsertAll(detail.albums.map { it.toEntity(gen) })
        }
    }

    override suspend fun refreshAlbum(id: String) {
        if (offlineMode()) return
        val detail = remote { it.getAlbum(id) }
        val sid = detail.album.serverId
        val gen = generation(sid)
        db.withTransaction {
            albumDao.upsert(detail.album.toEntity(gen))
            if (detail.tracks.isNotEmpty()) trackDao.upsertAll(detail.tracks.map { it.toEntity(gen) })
        }
    }

    override suspend fun refreshPlaylist(id: String) {
        if (offlineMode()) return
        val detail = remote { it.getPlaylist(id) }
        val sid = detail.playlist.serverId
        val gen = generation(sid)
        db.withTransaction {
            playlistDao.upsert(detail.playlist.copy(trackCount = detail.tracks.size).toEntity(gen))
            detail.tracks.chunked(IN_CHUNK).forEach { trackDao.upsertAll(it.map { t -> t.toEntity(gen) }) }
            playlistDao.replaceMembers(
                sid, id,
                detail.tracks.mapIndexed { index, t -> PlaylistTrackEntity(sid, id, t.id, index) },
            )
        }
    }

    override suspend fun refreshPlaylists() {
        if (offlineMode()) return
        val sid = requireServerId()
        val playlists = remote { it.getPlaylists() }
        val gen = generation(sid)
        db.withTransaction {
            if (playlists.isNotEmpty()) playlistDao.upsertAll(playlists.map { it.toEntity(gen) })
            val ids = playlists.map { it.id }
            if (ids.isEmpty()) {
                playlistDao.deleteAllMembers(sid)
                playlistDao.deleteByServer(sid)
            } else {
                playlistDao.deleteMembersExcept(sid, ids)
                playlistDao.deleteExcept(sid, ids)
            }
        }
    }

    override suspend fun refreshStarred() {
        if (offlineMode()) return
        val sid = requireServerId()
        val starred = remote { it.getStarred() }
        val gen = generation(sid)
        db.withTransaction {
            artistDao.clearStarred(sid)
            albumDao.clearStarred(sid)
            trackDao.clearStarred(sid)
            if (starred.artists.isNotEmpty()) artistDao.upsertAll(starred.artists.map { it.copy(starred = true).toEntity(gen) })
            if (starred.albums.isNotEmpty()) albumDao.upsertAll(starred.albums.map { it.copy(starred = true).toEntity(gen) })
            starred.tracks.chunked(IN_CHUNK).forEach { trackDao.upsertAll(it.map { t -> t.copy(starred = true).toEntity(gen) }) }
        }
    }

    // ---------------------------------------------------------------- remote-only reads

    override suspend fun getArtistTopTracks(artist: Artist, count: Int): List<Track> {
        if (offlineMode()) return emptyList()
        return remote { it.getTopTracks(artist, count) }.also { cacheTracks(it) }
    }

    override suspend fun getSimilarArtists(artistId: String): List<Artist> {
        if (offlineMode()) return emptyList()
        return remote { it.getArtist(artistId).similarArtists }
    }

    override suspend fun getSimilarTracks(trackId: String, count: Int): List<Track> {
        if (offlineMode()) return emptyList()
        return remote { it.getSimilarTracks(trackId, count) }.also { cacheTracks(it) }
    }

    override suspend fun getRandomTracks(size: Int, genre: String?): List<Track> {
        if (offlineMode()) return emptyList()
        return remote { it.getRandomTracks(size, genre) }.also { cacheTracks(it) }
    }

    override suspend fun getLyrics(track: Track): Lyrics? {
        if (offlineMode()) return null
        return remote { it.getLyrics(track) }
    }

    // ---------------------------------------------------------------- mutations

    override suspend fun setStarred(id: String, kind: ItemKind, starred: Boolean) {
        val sid = requireServerId()
        applyStarred(sid, id, kind, starred)
        try {
            remote { it.setStarred(id, kind, starred) }
        } catch (t: Throwable) {
            applyStarred(sid, id, kind, !starred)
            throw t
        }
    }

    private suspend fun applyStarred(sid: String, id: String, kind: ItemKind, starred: Boolean) {
        when (kind) {
            ItemKind.ARTIST -> artistDao.setStarred(sid, id, starred)
            ItemKind.ALBUM -> albumDao.setStarred(sid, id, starred)
            ItemKind.TRACK -> trackDao.setStarred(sid, id, starred)
            ItemKind.PLAYLIST -> Unit
        }
    }

    override suspend fun setRating(id: String, kind: ItemKind, rating: Int) {
        val sid = requireServerId()
        val previous = when (kind) {
            ItemKind.ARTIST -> artistDao.get(sid, id)?.rating
            ItemKind.ALBUM -> albumDao.get(sid, id)?.rating
            ItemKind.TRACK -> trackDao.get(sid, id)?.rating
            ItemKind.PLAYLIST -> null
        } ?: 0
        applyRating(sid, id, kind, rating)
        try {
            remote { it.setRating(id, kind, rating) }
        } catch (t: Throwable) {
            applyRating(sid, id, kind, previous)
            throw t
        }
    }

    private suspend fun applyRating(sid: String, id: String, kind: ItemKind, rating: Int) {
        when (kind) {
            ItemKind.ARTIST -> artistDao.setRating(sid, id, rating)
            ItemKind.ALBUM -> albumDao.setRating(sid, id, rating)
            ItemKind.TRACK -> trackDao.setRating(sid, id, rating)
            ItemKind.PLAYLIST -> Unit
        }
    }

    override suspend fun createPlaylist(name: String, trackIds: List<String>): Playlist {
        val created = remote { it.createPlaylist(name, trackIds) }
        val gen = generation(created.serverId)
        playlistDao.upsert(created.toEntity(gen))
        if (trackIds.isNotEmpty()) attempt { refreshPlaylist(created.id) }
        return created
    }

    override suspend fun renamePlaylist(id: String, name: String) {
        val sid = requireServerId()
        val previous = playlistDao.get(sid, id)?.name
        playlistDao.rename(sid, id, name)
        try {
            remote { it.updatePlaylist(id, name = name) }
        } catch (t: Throwable) {
            if (previous != null) playlistDao.rename(sid, id, previous)
            throw t
        }
    }

    override suspend fun addToPlaylist(playlistId: String, trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        val sid = requireServerId()
        val members = playlistDao.getMembers(sid, playlistId)
        val start = (members.maxOfOrNull { it.position } ?: -1) + 1
        db.withTransaction {
            playlistDao.insertMembers(trackIds.mapIndexed { i, trackId -> PlaylistTrackEntity(sid, playlistId, trackId, start + i) })
            playlistDao.setTrackCount(sid, playlistId, members.size + trackIds.size)
        }
        try {
            remote { it.updatePlaylist(playlistId, addTrackIds = trackIds) }
        } catch (t: Throwable) {
            db.withTransaction {
                playlistDao.replaceMembers(sid, playlistId, members)
                playlistDao.setTrackCount(sid, playlistId, members.size)
            }
            throw t
        }
        attempt { refreshPlaylist(playlistId) }
    }

    override suspend fun removeFromPlaylist(playlistId: String, indices: List<Int>) {
        if (indices.isEmpty()) return
        val sid = requireServerId()
        val members = playlistDao.getMembers(sid, playlistId)
        val remove = indices.toSet()
        val kept = members.filterIndexed { index, _ -> index !in remove }
            .mapIndexed { index, m -> m.copy(position = index) }
        db.withTransaction {
            playlistDao.replaceMembers(sid, playlistId, kept)
            playlistDao.setTrackCount(sid, playlistId, kept.size)
        }
        try {
            remote { it.updatePlaylist(playlistId, removeIndices = indices) }
        } catch (t: Throwable) {
            db.withTransaction {
                playlistDao.replaceMembers(sid, playlistId, members)
                playlistDao.setTrackCount(sid, playlistId, members.size)
            }
            throw t
        }
        attempt { refreshPlaylist(playlistId) }
    }

    override suspend fun deletePlaylist(id: String) {
        val sid = requireServerId()
        remote { it.deletePlaylist(id) }
        db.withTransaction {
            playlistDao.deleteMembers(sid, id)
            playlistDao.delete(sid, id)
        }
    }

    override suspend fun recordPlay(track: Track, playedAtEpochMs: Long) {
        db.withTransaction {
            historyDao.insert(
                PlayHistoryEntity(serverId = track.serverId, trackId = track.id, albumId = track.albumId, playedAt = playedAtEpochMs),
            )
            trackDao.bumpPlay(track.serverId, track.id, playedAtEpochMs)
            track.albumId?.let { albumDao.bumpPlay(track.serverId, it, playedAtEpochMs) }
            historyDao.prune(track.serverId, HISTORY_LIMIT)
        }
    }

    private companion object {
        const val IN_CHUNK = 500
        const val HISTORY_LIMIT = 5_000
    }
}

/** Rows per page for [LibraryRepository.pagedTracks]; a screen shows about fifteen. */
private const val TRACK_PAGE = 100
