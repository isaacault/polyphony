package app.polyphony.ui.browse.search

import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Artist
import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.CatalogAlbum
import app.polyphony.core.domain.model.CatalogArtist
import app.polyphony.core.domain.model.CatalogResults
import app.polyphony.core.domain.model.CatalogTrack
import app.polyphony.core.domain.model.Playlist
import app.polyphony.core.domain.model.Track
import app.polyphony.ui.browse.catalog.Discography

/**
 * Search scopes (#50), offered only for a continuo-linked server. ALL blends catalogue hits into
 * the library sections; LIBRARY is the library alone; DOWNLOADED keeps only what is on the phone.
 */
enum class SearchScope { ALL, LIBRARY, DOWNLOADED }

/**
 * Search's order inside each section: what the query names comes first. An exact name, then
 * names starting with it, then a word starting with it, then the rest in the order they came.
 */
object SearchRanking {
    fun <T> rank(items: List<T>, query: String, name: (T) -> String): List<T> {
        val q = loose(query)
        if (q.isEmpty()) return items
        return items.withIndex().sortedWith(compareBy({ score(loose(name(it.value)), q) }, { it.index })).map { it.value }
    }

    /** The name is what was typed (case, punctuation and a leading "The" aside). */
    fun isExact(name: String, query: String): Boolean = loose(query).let { it.isNotEmpty() && loose(name) == it }

    /** The name starts with what was typed ("maverick": Maverick Sabre). */
    fun startsWith(name: String, query: String): Boolean = loose(query).let { it.isNotEmpty() && loose(name).startsWith(it) }

    /**
     * How well [name] is what was typed, on continuo's scale: 3 the same, 2 it starts so, 1.5
     * every word typed starts one of its words, 1 it's inside, 0 not at all.
     */
    fun points(name: String, query: String): Float {
        val q = loose(query)
        val n = loose(name)
        if (q.isEmpty() || n.isEmpty()) return 0f
        if (n == q) return 3f
        if (n.startsWith(q)) return 2f
        val words = n.split(' ')
        if (q.split(' ').all { t -> words.any { it.startsWith(t) } }) return 1.5f
        return if (n.contains(q)) 1f else 0f
    }

    /** A name as a key for matching yours to the server's (case, punctuation and "The" aside). */
    fun nameKey(name: String): String = loose(name)

    private fun score(name: String, q: String): Int = when {
        name == q -> 0
        name.startsWith(q) -> 1
        name.split(' ').any { it.startsWith(q) } -> 2
        else -> 3
    }

    // Case and punctuation aside; spaces kept so words can be told apart. A leading "The" is
    // ignored, as in library sorting.
    private fun loose(s: String): String =
        s.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").trim()
            .replace(Regex("\\s+"), " ").removePrefix("the ")
}

/**
 * What leads a search page. One of yours named as typed (Search's exact matches, else an artist
 * or album of yours whose name starts so: "maverick" is your Maverick Sabre); else, from a
 * server that ranks its catalogue, the hit it names (continuo's `best`: "espresso" is Sabrina
 * Carpenter's song, "sabrina" Sabrina Carpenter).
 */
object TopHit {
    /** [items] as the lead (the one keyed [bestKey], if there) and the rest, in their order. */
    fun <T> byKey(items: List<T>, bestKey: String?, key: (T) -> String): Pair<List<T>, List<T>> {
        val at = if (bestKey == null) -1 else items.indexOfFirst { key(it) == bestKey }
        return if (at < 0) emptyList<T>() to items else listOf(items[at]) to items.filterIndexed { i, _ -> i != at }
    }

    /** [items] as the first whose name starts with what was typed, and the rest. */
    fun <T> startingWith(items: List<T>, query: String, name: (T) -> String): Pair<List<T>, List<T>> {
        val at = items.indexOfFirst { SearchRanking.startsWith(name(it), query) }
        return if (at < 0) emptyList<T>() to items else listOf(items[at]) to items.filterIndexed { i, _ -> i != at }
    }
}

/** One row of Top results, whatever it is. */
sealed interface TopItem {
    val key: String

    data class LibArtist(val artist: Artist) : TopItem { override val key get() = "la-" + artist.id }
    data class CatArtist(val artist: CatalogArtist) : TopItem { override val key get() = "ca-" + artist.key }
    data class LibAlbum(val album: Album) : TopItem { override val key get() = "lb-" + album.id }
    data class CatAlbum(val album: CatalogAlbum) : TopItem { override val key get() = "cb-" + album.key }
    data class LibPlaylist(val playlist: Playlist) : TopItem { override val key get() = "lp-" + playlist.id }
    data class LibTrack(val track: Track) : TopItem { override val key get() = "lt-" + track.id }
    data class CatTrack(val track: CatalogTrack) : TopItem { override val key get() = "ct-" + track.key }
}

/**
 * Top results as one list, like Spotify's ("lil": Lil Wayne, Lil Peep, Lil Uzi Vert, an album,
 * Lil Baby…): yours and the server's together, each scored as continuo scores its lists: how
 * well its own name is what was typed, plus its popularity (the server's, by key, or for one of
 * yours by name; else [LIBRARY_POP]), plus a lean towards artists, then albums, then playlists,
 * then songs, and a little for yours. The server's best hit gets a lift. Only what matches by
 * name (or is the server's best) is in it.
 */
object TopBlend {
    const val SIZE = 8
    private const val POP_WEIGHT = 0.75f
    private const val LIBRARY_POP = 4.5f
    private const val YOURS = 0.3f
    private const val BEST = 2f
    private const val ARTIST = 1.2f
    private const val ALBUM = 0.6f
    private const val PLAYLIST = 0.4f

    fun build(
        query: String,
        artists: List<Artist>, albums: List<Album>, playlists: List<Playlist>, tracks: List<Track>,
        catArtists: List<CatalogArtist>, catAlbums: List<CatalogAlbum>, catTracks: List<CatalogTrack>,
        popularity: Map<String, Float>, popularityByName: Map<String, Float>, bestKey: String?,
    ): List<TopItem> {
        fun songPoints(title: String, artist: String?): Float {
            val own = SearchRanking.points(title, query)
            if (own > 0f || artist == null || query.trim().split(' ').size < 2) return own
            return maxOf(SearchRanking.points("$title $artist", query), SearchRanking.points("$artist $title", query)) - 0.25f
        }
        fun yours(name: String) = popularityByName[SearchRanking.nameKey(name)] ?: LIBRARY_POP
        val scored = buildList {
            artists.forEach { add(TopItem.LibArtist(it) to score(SearchRanking.points(it.name, query), yours(it.name), ARTIST + YOURS, false)) }
            albums.forEach { add(TopItem.LibAlbum(it) to score(SearchRanking.points(it.name, query), yours(it.name), ALBUM + YOURS, false)) }
            playlists.forEach { add(TopItem.LibPlaylist(it) to score(SearchRanking.points(it.name, query), LIBRARY_POP, PLAYLIST + YOURS, false)) }
            tracks.forEach { add(TopItem.LibTrack(it) to score(songPoints(it.title, it.artistName), yours(it.title), YOURS, false)) }
            catArtists.forEach { add(TopItem.CatArtist(it) to score(SearchRanking.points(it.name, query), popularity[it.key] ?: 0f, ARTIST, it.key == bestKey)) }
            catAlbums.forEach { add(TopItem.CatAlbum(it) to score(SearchRanking.points(it.title, query), popularity[it.key] ?: 0f, ALBUM, it.key == bestKey)) }
            catTracks.forEach { add(TopItem.CatTrack(it) to score(songPoints(it.title, it.artist), popularity[it.key] ?: 0f, 0f, it.key == bestKey)) }
        }
        return scored.filter { it.second > 0f }.sortedByDescending { it.second }.take(SIZE).map { it.first }
    }

    private fun score(points: Float, pop: Float, boost: Float, best: Boolean): Float =
        if (points == 0f && !best) 0f else points + POP_WEIGHT * pop + boost + if (best) BEST else 0f
}

/**
 * The type chips above the filters: ALL shows every section, the others one section with room for
 * more results in it.
 */
enum class SearchKind {
    ALL, ARTISTS, ALBUMS, SONGS, PLAYLISTS;

    fun shows(kind: SearchKind): Boolean = this == ALL || this == kind

    /** How many local matches to load per section. */
    val localLimit: Int get() = if (this == ALL) 30 else 100
}

/**
 * The Everything section: catalogue hits that aren't in the library (the library sections already
 * show those), with this session's requests applied. [requested] is keyed by MusicBrainz release
 * group, so requesting an album also marks its tracks, and vice versa.
 */
object EverythingSection {

    /** Catalogue artists with nothing in the library (the Artists section already lists those). */
    fun artists(results: CatalogResults?): List<CatalogArtist> =
        results?.artists.orEmpty().filter { it.availability == Availability.CatalogOnly && it.musicBrainzId != null }

    fun albums(results: CatalogResults?, requested: Map<String, Availability>): List<CatalogAlbum> =
        results?.albums.orEmpty()
            .map { a -> a.releaseGroupId?.let(requested::get)?.let { a.copy(availability = it) } ?: a }
            .filterNot { it.availability.isInLibrary() }

    fun tracks(results: CatalogResults?, requested: Map<String, Availability>): List<CatalogTrack> =
        results?.tracks.orEmpty()
            .map { t -> t.requestKeys.firstNotNullOfOrNull(requested::get)?.let { t.copy(availability = it) } ?: t }
            .filterNot { it.availability.isInLibrary() }

    /**
     * Library albums the catalogue only partly matches, by library album id: their library row
     * says how many of the release's tracks you have instead of the catalogue repeating the album.
     */
    fun partials(results: CatalogResults?): Map<String, Availability.Partial> = Discography.partials(results?.albums.orEmpty())

    /**
     * True when there's something to request: a release group and nothing under way or owned.
     * An album on the server but not in this account's library counts (the request adds it), and
     * so does one you only have single songs of.
     */
    fun canRequest(availability: Availability, releaseGroupId: String?): Boolean =
        releaseGroupId != null && (availability == Availability.Requestable || availability is Availability.OnServer ||
            (availability is Availability.Partial && availability.requestable))

    private fun Availability.isInLibrary(): Boolean =
        this is Availability.InLibrary || (this is Availability.Partial && !requestable)
}
