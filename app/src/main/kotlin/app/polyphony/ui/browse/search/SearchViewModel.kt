package app.polyphony.ui.browse.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.polyphony.R
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Artist
import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.CatalogAlbum
import app.polyphony.core.domain.model.CatalogArtist
import app.polyphony.core.domain.model.CatalogResults
import app.polyphony.core.domain.model.CatalogTrack
import app.polyphony.core.domain.model.Feedback
import app.polyphony.core.domain.model.FilterField
import app.polyphony.core.domain.model.ItemKind
import app.polyphony.core.domain.model.LibraryFilter
import app.polyphony.core.domain.model.Playlist
import app.polyphony.core.domain.model.SearchResults
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.playback.PlaybackController
import app.polyphony.core.domain.provider.ProviderException
import app.polyphony.core.domain.repository.FeedbackRepository
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.ui.browse.catalog.CatalogRequester
import app.polyphony.ui.common.ArtistNames
import app.polyphony.ui.common.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The Everything tier of a search (continuo-linked servers, ALL scope). */
data class EverythingUi(
    val artists: ImmutableList<CatalogArtist> = persistentListOf(),
    val albums: ImmutableList<CatalogAlbum> = persistentListOf(),
    val tracks: ImmutableList<CatalogTrack> = persistentListOf(),
    val searching: Boolean = false,
    val searched: Boolean = false,
    val failed: Boolean = false,
    /** The server answered but its catalogue (Lidarr / MusicBrainz) was down. */
    val catalogDown: Boolean = false,
    /** Release groups with a request in flight from this screen. */
    val requesting: ImmutableSet<String> = persistentSetOf(),
    /** Library albums the catalogue only partly matches ([EverythingSection.partials]). */
    val partials: ImmutableMap<String, Availability.Partial> = persistentMapOf(),
    /** Only the top few, found while typing: the search button brings the rest. */
    val quick: Boolean = false,
    /** The server ranked these (TopHit): their order stands, [bestKey] leads. */
    val ranked: Boolean = false,
    val bestKey: String? = null,
    /** Popularity by hit key, and by name for matching yours (TopBlend). */
    val popularity: ImmutableMap<String, Float> = persistentMapOf(),
    val popularityByName: ImmutableMap<String, Float> = persistentMapOf(),
    /** The server is being asked about what's typed and hasn't answered yet: skeleton rows. */
    val pending: Boolean = false,
) {
    val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && tracks.isEmpty()
}

/**
 * Exact name matches, pulled out of their sections and shown first, like Spotify's top result:
 * artists, then albums, playlists and songs; yours, then the catalogue's.
 */
data class TopResults(
    /** All of it in one order (TopBlend's, from a ranking server; else by kind). */
    val items: ImmutableList<TopItem> = persistentListOf(),
    val artists: ImmutableList<Artist> = persistentListOf(),
    val catalogArtists: ImmutableList<CatalogArtist> = persistentListOf(),
    val albums: ImmutableList<Album> = persistentListOf(),
    val catalogAlbums: ImmutableList<CatalogAlbum> = persistentListOf(),
    val playlists: ImmutableList<Playlist> = persistentListOf(),
    val tracks: ImmutableList<Track> = persistentListOf(),
    val catalogTracks: ImmutableList<CatalogTrack> = persistentListOf(),
) {
    val isLibraryEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && playlists.isEmpty() && tracks.isEmpty()
    val isEmpty: Boolean get() = isLibraryEmpty && catalogArtists.isEmpty() && catalogAlbums.isEmpty() && catalogTracks.isEmpty()
}

data class SearchUiState(
    val query: String = "",
    val artists: ImmutableList<Artist> = persistentListOf(),
    val albums: ImmutableList<Album> = persistentListOf(),
    val tracks: ImmutableList<Track> = persistentListOf(),
    val playlists: ImmutableList<Playlist> = persistentListOf(),
    val recentSearches: ImmutableList<String> = persistentListOf(),
    val isSearchingRemote: Boolean = false,
    val remoteSearched: Boolean = false,
    val remoteFailed: Boolean = false,
    val currentTrackId: String? = null,
    val isPlaying: Boolean = false,
    /** True when the active server is continuo-linked: the scope chips and Everything show. */
    val scopesAvailable: Boolean = false,
    val scope: SearchScope = SearchScope.LIBRARY,
    val kind: SearchKind = SearchKind.ALL,
    val top: TopResults = TopResults(),
    val everything: EverythingUi = EverythingUi(),
    /** Narrows the library sections; starts empty every time Search opens. */
    val filter: LibraryFilter = LibraryFilter.NONE,
    val feedback: Feedback = Feedback(),
) {
    val hasQuery: Boolean get() = query.isNotBlank()
    val isLibraryEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && tracks.isEmpty() && playlists.isEmpty() && top.isLibraryEmpty
    val isEmpty: Boolean get() = isLibraryEmpty && everything.isEmpty && top.isEmpty

    /** What a tapped song plays through: the top-result songs, then the Songs section. */
    val playable: List<Track> get() = top.tracks + tracks
    val showsEverything: Boolean get() = scopesAvailable && scope == SearchScope.ALL && !filter.isActive

    /**
     * A search the user can still start for this term: the server, and the Everything tier where
     * it shows. Downloaded results are all local, so there is nothing further to search for them.
     */
    val canSearchFurther: Boolean
        get() = !filter.downloadedOnly &&
            (!remoteSearched || (showsEverything && !everything.searched && !everything.searching))

    /** Downloaded is a scope chip on continuo servers, so the filter row leaves it out there. */
    val filterFields: Set<FilterField>
        get() = feedback.filterFields(LibraryFilter.fieldsFor(ItemKind.TRACK)).let { if (scopesAvailable) it - FilterField.DOWNLOADED else it }
}

private data class RemoteState(
    val forQuery: String = "",
    val results: SearchResults? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
)

private data class CatalogState(
    val forQuery: String = "",
    val results: CatalogResults? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
    /** The quick search while typing, not the full one ([SearchViewModel.searchQuick]). */
    val quick: Boolean = false,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val playback: PlaybackController,
    private val servers: ServerRepository,
    private val requests: RequestRepository,
    private val feedbackRepository: FeedbackRepository,
    private val artistNames: ArtistNames,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val recent = MutableStateFlow<List<String>>(emptyList())
    private val remote = MutableStateFlow(RemoteState())
    private val catalog = MutableStateFlow(CatalogState())
    private val chosenScope = MutableStateFlow(SearchScope.ALL)
    private val kind = MutableStateFlow(SearchKind.ALL)
    private val filter = MutableStateFlow(LibraryFilter.NONE)
    private var remoteJob: Job? = null
    private var catalogJob: Job? = null
    private var quickJob: Job? = null
    /** The query a quick search is out for (skeleton rows until it answers). */
    private val quickPending = MutableStateFlow<String?>(null)

    /** The last submitted term: switching scope re-runs the searches that scope needs. */
    private var submitted: String? = null

    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    private val requester = CatalogRequester(viewModelScope, servers, requests, messages)
    val events: Flow<UiMessage> = messages.receiveAsFlow()

    private val linked: StateFlow<Boolean> = servers.activeServer
        .map { requests.supports(it) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Without a continuo server there are no scopes: search behaves exactly as before (LIBRARY). */
    private val scope: Flow<SearchScope> = combine(chosenScope, linked) { s, l -> if (l) s else SearchScope.LIBRARY }
        .distinctUntilChanged()

    /** The chips plus the Downloaded scope, which is the same narrowing. */
    private val effectiveFilter: Flow<LibraryFilter> = combine(filter, scope, feedbackRepository.feedback) { f, s, fb ->
        fb.applyTo(if (s == SearchScope.DOWNLOADED) f.copy(downloadedOnly = true) else f)
    }.distinctUntilChanged()

    private val localResults = combine(query.map { it.trim() }.debounce(150).distinctUntilChanged(), effectiveFilter, kind) { q, f, k -> Triple(q, f, k.localLimit) }
        .distinctUntilChanged()
        .flatMapLatest { (q, f, limit) ->
            if (q.isBlank()) flowOf(SearchResults()) else combine(library.searchLocal(q, limit, f), byOriginalName(q, f)) { found, extra ->
                if (extra.isEmpty()) found else found.copy(artists = (extra + found.artists).distinctBy { it.id })
            }
        }

    private val matchingPlaylists = combine(
        query.map { it.trim() }.debounce(150),
        effectiveFilter.flatMapLatest { f ->
            if (f.excludesAll(ItemKind.PLAYLIST)) flowOf(emptyList()) else library.playlists(f.restrictedTo(ItemKind.PLAYLIST))
        },
        kind,
    ) { q, playlists, k ->
        if (q.isBlank()) emptyList() else playlists.filter { it.name.contains(q, ignoreCase = true) }.take(if (k == SearchKind.ALL) 10 else k.localLimit)
    }

    /** Library artists whose original name was typed (フィッシュマンズ finds Fishmans). */
    private fun byOriginalName(q: String, f: LibraryFilter): Flow<List<Artist>> = artistNames.names.map { names ->
        ArtistNames.matching(names, q).flatMap { name ->
            library.searchLocal(name, 5, f).first().artists.filter { it.name.equals(name, ignoreCase = true) }
        }
    }

    private val libraryState: Flow<SearchUiState> = combine(
        query, localResults, matchingPlaylists, combine(remote, effectiveFilter) { r, f -> r to f }, recent,
    ) { q, local, playlists, (remoteState, f), recentList ->
        val remoteResults = remoteState.results?.takeIf { remoteState.forQuery == q.trim() }?.let { filterRemote(it, f) }
        SearchUiState(
            query = q,
            filter = f,
            artists = merge(local.artists, remoteResults?.artists) { it.id },
            albums = merge(local.albums, remoteResults?.albums) { it.id },
            tracks = merge(local.tracks, remoteResults?.tracks) { it.id },
            playlists = playlists.toImmutableList(),
            recentSearches = recentList.toImmutableList(),
            isSearchingRemote = remoteState.loading && remoteState.forQuery == q.trim(),
            remoteSearched = remoteResults != null,
            remoteFailed = remoteState.failed && remoteState.forQuery == q.trim(),
        )
    }

    private val everythingState: Flow<Triple<Boolean, SearchScope, EverythingUi>> = combine(
        linked, scope, query, catalog, combine(requester.requested, requester.requesting, quickPending) { r, busy, p -> Triple(r, busy, p) },
    ) { isLinked, s, q, cat, (req, busy, quickFor) ->
        // While typing, the last quick results stay up until the next letter's arrive: "sabr"'s
        // for "sabri", rather than a blank list between keystrokes.
        val current = cat.forQuery == q.trim() || (cat.quick && cat.forQuery.isNotEmpty() && q.trim().startsWith(cat.forQuery, ignoreCase = true))
        val results = cat.results?.takeIf { current }
        Triple(
            isLinked,
            s,
            EverythingUi(
                artists = EverythingSection.artists(results).toImmutableList(),
                albums = EverythingSection.albums(results, req).toImmutableList(),
                tracks = EverythingSection.tracks(results, req).toImmutableList(),
                searching = cat.loading && current && !cat.quick,   // quick ones come without a progress bar
                searched = results != null && !cat.quick,
                failed = cat.failed && current,
                catalogDown = results?.catalogAvailable == false,
                requesting = busy.toImmutableSet(),
                partials = EverythingSection.partials(results).toImmutableMap(),
                quick = cat.quick && results != null,
                ranked = results?.ranked == true,
                bestKey = results?.bestKey,
                popularity = results?.popularity.orEmpty().toImmutableMap(),
                popularityByName = popularityByName(results).toImmutableMap(),
                pending = quickFor == q.trim() && results == null,
            ),
        )
    }

    val state: StateFlow<SearchUiState> = combine(libraryState, everythingState, playback.state, feedbackRepository.feedback, kind) { s, (isLinked, sc, every), pb, fb, k ->
        val shown = if (isLinked && sc == SearchScope.ALL && !s.filter.isActive) every else EverythingUi()
        val q = s.query
        // Exact matches lead on All; on a type chip they simply come first in that one section.
        val lead = k == SearchKind.ALL
        fun <T> ranked(on: Boolean, items: List<T>, name: (T) -> String) =
            if (on) SearchRanking.rank(items, q, name) else emptyList()
        fun <T> split(on: Boolean, items: List<T>, name: (T) -> String): Pair<List<T>, List<T>> =
            ranked(on, items, name).partition { lead && SearchRanking.isExact(name(it), q) }
        if (lead && shown.ranked) return@combine blendedState(s, shown, pb, fb, k, isLinked, sc)
        var (topArtists, artists) = split(k.shows(SearchKind.ARTISTS), s.artists) { it.name }
        var (topAlbums, albums) = split(k.shows(SearchKind.ALBUMS), s.albums) { it.name }
        val (topPlaylists, playlists) = split(k.shows(SearchKind.PLAYLISTS), s.playlists) { it.name }
        val (topTracks, tracks) = split(k.shows(SearchKind.SONGS), s.tracks) { it.title }
        // One of yours named as typed leads (TopHit); a ranking server's best leads otherwise.
        if (lead && shown.ranked && (topArtists + topAlbums + topPlaylists + topTracks).isEmpty()) {
            TopHit.startingWith(artists, q) { it.name }.let { (t, rest) -> topArtists = t; artists = rest }
            if (topArtists.isEmpty()) TopHit.startingWith(albums, q) { it.name }.let { (t, rest) -> topAlbums = t; albums = rest }
        }
        val yoursLead = (topArtists + topAlbums + topPlaylists + topTracks).isNotEmpty()
        // A ranked catalogue keeps the server's order (most listened first: "sabrina" is Sabrina
        // Carpenter, not an artist called Sabrina) and its best hit leads; an older server's is
        // ordered here, its exact names leading, as before.
        fun <T> catalog(on: Boolean, items: List<T>, name: (T) -> String, key: (T) -> String): Pair<List<T>, List<T>> = when {
            !on -> emptyList<T>() to emptyList()
            shown.ranked -> if (lead && !yoursLead) TopHit.byKey(items, shown.bestKey, key) else emptyList<T>() to items
            else -> split(true, items, name)
        }
        val (topCatArtists, catArtists) = catalog(k.shows(SearchKind.ARTISTS), shown.artists, { it.name }, { it.key })
        val (topCatAlbums, catAlbums) = catalog(k.shows(SearchKind.ALBUMS), shown.albums, { it.title }, { it.key })
        val (topCatTracks, catTracks) = catalog(k.shows(SearchKind.SONGS), shown.tracks, { it.title }, { it.key })
        s.copy(
            feedback = fb,
            scopesAvailable = isLinked,
            scope = sc,
            kind = k,
            top = TopResults(
                items = (topArtists.map(TopItem::LibArtist) + topCatArtists.map(TopItem::CatArtist) + topAlbums.map(TopItem::LibAlbum) +
                    topCatAlbums.map(TopItem::CatAlbum) + topPlaylists.map(TopItem::LibPlaylist) + topTracks.map(TopItem::LibTrack) +
                    topCatTracks.map(TopItem::CatTrack)).toImmutableList(),
                artists = topArtists.toImmutableList(), catalogArtists = topCatArtists.toImmutableList(),
                albums = topAlbums.toImmutableList(), catalogAlbums = topCatAlbums.toImmutableList(),
                playlists = topPlaylists.toImmutableList(),
                tracks = topTracks.toImmutableList(), catalogTracks = topCatTracks.toImmutableList(),
            ),
            artists = artists.toImmutableList(),
            albums = albums.toImmutableList(),
            tracks = tracks.toImmutableList(),
            playlists = playlists.toImmutableList(),
            everything = shown.copy(
                artists = catArtists.toImmutableList(),
                albums = catAlbums.toImmutableList(),
                tracks = catTracks.toImmutableList(),
            ),
            currentTrackId = pb.currentTrack?.id,
            isPlaying = pb.isPlaying,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    /**
     * All (on a ranking server): Top results is one list across kinds, yours and the server's
     * blended (TopBlend), and the sections below hold the rest, in their own orders.
     */
    private fun blendedState(
        s: SearchUiState, shown: EverythingUi, pb: app.polyphony.core.domain.playback.PlaybackState,
        fb: app.polyphony.core.domain.model.Feedback, k: SearchKind, isLinked: Boolean, sc: SearchScope,
    ): SearchUiState {
        val q = s.query
        val items = TopBlend.build(
            q, s.artists, s.albums, s.playlists, s.tracks, shown.artists, shown.albums, shown.tracks,
            shown.popularity, shown.popularityByName, shown.bestKey,
        )
        val used = items.map { it.key }.toSet()
        return s.copy(
            feedback = fb, scopesAvailable = isLinked, scope = sc, kind = k,
            top = TopResults(
                items = items.toImmutableList(),
                artists = items.filterIsInstance<TopItem.LibArtist>().map { it.artist }.toImmutableList(),
                catalogArtists = items.filterIsInstance<TopItem.CatArtist>().map { it.artist }.toImmutableList(),
                albums = items.filterIsInstance<TopItem.LibAlbum>().map { it.album }.toImmutableList(),
                catalogAlbums = items.filterIsInstance<TopItem.CatAlbum>().map { it.album }.toImmutableList(),
                playlists = items.filterIsInstance<TopItem.LibPlaylist>().map { it.playlist }.toImmutableList(),
                tracks = items.filterIsInstance<TopItem.LibTrack>().map { it.track }.toImmutableList(),
                catalogTracks = items.filterIsInstance<TopItem.CatTrack>().map { it.track }.toImmutableList(),
            ),
            artists = SearchRanking.rank(s.artists, q) { it.name }.filterNot { "la-" + it.id in used }.toImmutableList(),
            albums = SearchRanking.rank(s.albums, q) { it.name }.filterNot { "lb-" + it.id in used }.toImmutableList(),
            playlists = SearchRanking.rank(s.playlists, q) { it.name }.filterNot { "lp-" + it.id in used }.toImmutableList(),
            tracks = SearchRanking.rank(s.tracks, q) { it.title }.filterNot { "lt-" + it.id in used }.toImmutableList(),
            everything = shown.copy(
                artists = shown.artists.filterNot { "ca-" + it.key in used }.toImmutableList(),
                albums = shown.albums.filterNot { "cb-" + it.key in used }.toImmutableList(),
                tracks = shown.tracks.filterNot { "ct-" + it.key in used }.toImmutableList(),
            ),
            currentTrackId = pb.currentTrack?.id,
            isPlaying = pb.isPlaying,
        )
    }

    /** Choices for the Genre filter chip. */
    val genreNames: StateFlow<ImmutableList<String>> = library.genres()
        .map { list -> list.map { it.name }.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    /** The library's year span, for the Year filter chip. */
    val yearRange: StateFlow<IntRange?> = library.yearRange()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun onQueryChange(value: String) {
        query.value = value
        if (value.isBlank()) {
            quickJob?.cancel()
            remote.value = RemoteState()
            catalog.value = CatalogState()
            submitted = null
        } else {
            searchQuick()
        }
    }

    /**
     * Search while typing: a moment after the last letter, the top few from continuo's catalogue
     * and the library ("bey": Beyoncé), most listened first. The full search (more of everything,
     * songs from MusicBrainz) is still the search button, and replaces these.
     */
    private fun searchQuick() {
        quickJob?.cancel()
        val q = query.value.trim()
        if (q.length < QUICK_MIN_CHARS || !linked.value || effectiveScope() != SearchScope.ALL || filter.value.isActive) return
        quickJob = viewModelScope.launch {
            delay(QUICK_DEBOUNCE_MS)
            quickPending.value = q
            if (catalog.value.let { !it.quick && it.forQuery == q }) return@launch    // the full search has it
            val server = servers.getActive()?.takeIf { requests.supports(it) } ?: return@launch
            val results = try {
                requests.search(server, q, limit = QUICK_LIMIT, quick = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch   // quietly: the search button still searches everything
            }
            // A full search started meanwhile wins; a stale quick one doesn't replace a newer query's.
            if (catalog.value.let { !it.quick && it.forQuery.isNotEmpty() && it.forQuery == q } || query.value.trim() != q) return@launch
            catalog.value = CatalogState(forQuery = q, results = results, quick = true)
        }.also { job -> job.invokeOnCompletion { if (quickPending.value == q) quickPending.value = null } }
    }

    fun clear() = onQueryChange("")

    /** Called on IME search, the search button or a recent pick: remember the term and hit the server(s). */
    fun submit() {
        val q = query.value.trim()
        if (q.isBlank()) return
        rememberSearch(q)
        submitted = q
        runScopeSearches(force = true)
    }

    fun setScope(value: SearchScope) {
        if (chosenScope.value == value) return
        chosenScope.value = value
        if (value == SearchScope.DOWNLOADED) {
            remoteJob?.cancel()
            remote.value = RemoteState()
            catalogJob?.cancel()
            quickJob?.cancel()
            catalog.value = CatalogState()
        }
        runScopeSearches(force = false)
        if (submitted == null) searchQuick()
    }

    /**
     * A type chip: that section alone. Asking for one kind says the top few aren't enough, so
     * the full search runs at once (more of that kind from the server), as pressing search would.
     */
    fun setKind(value: SearchKind) {
        kind.value = value
        val q = query.value.trim()
        if (value != SearchKind.ALL && value != SearchKind.PLAYLISTS && q.isNotBlank() && submitted != q) {
            submitted = q
            runScopeSearches(force = false)
        }
    }

    fun setFilter(value: LibraryFilter) {
        if (filter.value == value) return
        filter.value = value
        // Clearing the filters brings the Everything tier back; fetch it if this term never had it.
        if (!value.isActive) runScopeSearches(force = false)
    }

    /**
     * Server results narrowed the way the local SQL narrows the cache. Downloads are all cached,
     * so the local results already hold every downloaded match: a Downloaded filter drops these.
     */
    private fun filterRemote(results: SearchResults, f: LibraryFilter): SearchResults {
        if (!f.isActive) return results
        if (f.downloadedOnly) return SearchResults()
        return SearchResults(
            artists = if (f.excludesAll(ItemKind.ARTIST)) emptyList() else results.artists.filter(f::matches),
            albums = if (f.excludesAll(ItemKind.ALBUM)) emptyList() else results.albums.filter(f::matches),
            tracks = results.tracks.filter(f::matches),
        )
    }

    private fun effectiveScope(): SearchScope = if (linked.value) chosenScope.value else SearchScope.LIBRARY

    private fun runScopeSearches(force: Boolean) {
        val q = submitted?.takeIf { it == query.value.trim() } ?: return
        val s = effectiveScope()
        if (s != SearchScope.DOWNLOADED && (force || remote.value.forQuery != q)) searchRemote()
        if (s == SearchScope.ALL && linked.value && !filter.value.isActive && (force || catalog.value.forQuery != q)) searchEverything()
    }

    fun searchRemote() {
        val q = query.value.trim()
        if (q.isBlank() || effectiveScope() == SearchScope.DOWNLOADED || filter.value.downloadedOnly) return
        remoteJob?.cancel()
        remote.value = RemoteState(forQuery = q, loading = true)
        remoteJob = viewModelScope.launch {
            val result = runCatching { library.searchRemote(q) }
            remote.value = RemoteState(
                forQuery = q,
                results = result.getOrNull(),
                loading = false,
                failed = result.isFailure,
            )
        }
    }

    private fun searchEverything() {
        val q = query.value.trim()
        if (q.isBlank()) return
        quickJob?.cancel()
        catalogJob?.cancel()
        catalog.value = CatalogState(forQuery = q, loading = true)
        catalogJob = viewModelScope.launch {
            val server = servers.getActive()?.takeIf { requests.supports(it) }
            val result = if (server == null) {
                Result.failure(IllegalStateException("not a continuo server"))
            } else {
                try {
                    Result.success(requests.search(server, q))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e is ProviderException.Unauthorized) messages.send(UiMessage(R.string.browse_request_sign_in))
                    Result.failure(e)
                }
            }
            catalog.value = CatalogState(forQuery = q, results = result.getOrNull(), loading = false, failed = result.isFailure)
        }
    }

    /** Request the album with this MusicBrainz release group (an album row, or a track's album). */
    fun request(releaseGroupId: String) = requester.request(releaseGroupId)

    /** Request a song row: the song itself where the server can, else its album. */
    fun requestTrack(track: CatalogTrack) = requester.requestTrack(track)

    fun rememberSearch(term: String) {
        val t = term.trim()
        if (t.isBlank()) return
        recent.value = (listOf(t) + recent.value.filterNot { it.equals(t, ignoreCase = true) }).take(10)
    }

    fun removeRecent(term: String) {
        recent.value = recent.value - term
    }

    fun playTrack(track: Track) {
        val tracks = state.value.playable
        val index = tracks.indexOf(track)
        if (index < 0) return
        rememberSearch(query.value)
        playback.playTracks(tracks, index, queueTitle = "Search: ${query.value.trim()}")
    }

    private fun <T> merge(local: List<T>, remote: List<T>?, id: (T) -> String): ImmutableList<T> {
        if (remote.isNullOrEmpty()) return local.toImmutableList()
        val seen = local.mapTo(HashSet()) { id(it) }
        return (local + remote.filter { seen.add(id(it)) }).toImmutableList()
    }
}

/** Search while typing: from the second letter, 300 ms after the last one, the top 3 of each. */
private const val QUICK_MIN_CHARS = 2
private const val QUICK_DEBOUNCE_MS = 300L
private const val QUICK_LIMIT = 5

/** Popularity by name (yours matched to the server's hits, which include yours). */
private fun popularityByName(results: CatalogResults?): Map<String, Float> {
    if (results == null) return emptyMap()
    val pop = results.popularity
    return buildMap {
        results.artists.forEach { a -> pop[a.key]?.let { put(SearchRanking.nameKey(a.name), it) } }
        results.albums.forEach { a -> pop[a.key]?.let { putIfAbsent(SearchRanking.nameKey(a.title), it) } }
        results.tracks.forEach { t -> pop[t.key]?.let { putIfAbsent(SearchRanking.nameKey(t.title), it) } }
    }
}

