package app.polyphony.ui.browse.search

import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.CatalogAlbum
import app.polyphony.core.domain.model.CatalogArtist
import app.polyphony.core.domain.model.CatalogResults
import app.polyphony.core.domain.model.CatalogTrack
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
            .map { t -> t.albumReleaseGroupId?.let(requested::get)?.let { t.copy(availability = it) } ?: t }
            .filterNot { it.availability.isInLibrary() }

    /**
     * Library albums the catalogue only partly matches, by library album id: their library row
     * says how many of the release's tracks you have instead of the catalogue repeating the album.
     */
    fun partials(results: CatalogResults?): Map<String, Availability.Partial> = Discography.partials(results?.albums.orEmpty())

    /** True when there's something to request: a release group and nothing under way or owned. */
    fun canRequest(availability: Availability, releaseGroupId: String?): Boolean =
        releaseGroupId != null && availability == Availability.Requestable

    private fun Availability.isInLibrary(): Boolean =
        this is Availability.InLibrary || this is Availability.Partial
}
