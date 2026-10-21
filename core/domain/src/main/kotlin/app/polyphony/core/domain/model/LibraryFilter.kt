package app.polyphony.core.domain.model

import kotlinx.serialization.Serializable

/** One narrowing a [LibraryFilter] can apply. Which ones a list offers depends on what its items carry. */
enum class FilterField { FAVOURITES, RATING, DOWNLOADED, GENRE, YEAR }

/**
 * What a library list or a search is narrowed to. Every field is an AND; the defaults filter
 * nothing ([NONE]). Persisted per Library tab next to its sort.
 *
 * Downloaded is resolved against the downloads index (a collection counts when it holds at least
 * one downloaded track); everything else is a column of the cached item.
 */
@Serializable
data class LibraryFilter(
    val favouritesOnly: Boolean = false,
    /** 0 = any; otherwise items rated at least this many stars (1..5). */
    val minRating: Int = 0,
    val downloadedOnly: Boolean = false,
    val genre: String? = null,
    /** Inclusive bounds; either may be open. */
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
) {
    val isActive: Boolean get() = this != NONE

    /** The fields this filter actually narrows by. */
    val activeFields: Set<FilterField>
        get() = buildSet {
            if (favouritesOnly) add(FilterField.FAVOURITES)
            if (minRating > 0) add(FilterField.RATING)
            if (downloadedOnly) add(FilterField.DOWNLOADED)
            if (genre != null) add(FilterField.GENRE)
            if (yearFrom != null || yearTo != null) add(FilterField.YEAR)
        }

    /** This filter without the fields [kind] has no data for (an artist has no year, a playlist no rating). */
    fun restrictedTo(kind: ItemKind): LibraryFilter {
        val allowed = fieldsFor(kind)
        return LibraryFilter(
            favouritesOnly = favouritesOnly && FilterField.FAVOURITES in allowed,
            minRating = if (FilterField.RATING in allowed) minRating else 0,
            downloadedOnly = downloadedOnly && FilterField.DOWNLOADED in allowed,
            genre = genre.takeIf { FilterField.GENRE in allowed },
            yearFrom = yearFrom.takeIf { FilterField.YEAR in allowed },
            yearTo = yearTo.takeIf { FilterField.YEAR in allowed },
        )
    }

    /**
     * True when an active field cannot hold for any item of [kind], so a mixed result (search)
     * should leave that section out rather than ignore the filter. A ★4+ search shows no playlists.
     */
    fun excludesAll(kind: ItemKind): Boolean = !fieldsFor(kind).containsAll(activeFields)

    // In-memory twins of the SQL in core:data, for items that did not come from the cache (server
    // search results). Downloaded is not checked here: it needs the downloads index.

    fun matches(track: Track): Boolean =
        (!favouritesOnly || track.starred) &&
            track.rating >= minRating &&
            matchesGenre(track.genre) &&
            matchesYear(track.year)

    fun matches(album: Album): Boolean =
        (!favouritesOnly || album.starred) &&
            album.rating >= minRating &&
            matchesGenre(album.genre) &&
            matchesYear(album.year)

    fun matches(artist: Artist): Boolean =
        (!favouritesOnly || artist.starred) && artist.rating >= minRating

    private fun matchesGenre(value: String?): Boolean = genre == null || genre.equals(value, ignoreCase = true)

    private fun matchesYear(value: Int?): Boolean {
        if (yearFrom == null && yearTo == null) return true
        if (value == null || value <= 0) return false
        return (yearFrom == null || value >= yearFrom) && (yearTo == null || value <= yearTo)
    }

    companion object {
        val NONE = LibraryFilter()

        /** The narrowings that make sense for a list of [kind]. */
        fun fieldsFor(kind: ItemKind): Set<FilterField> = when (kind) {
            ItemKind.TRACK, ItemKind.ALBUM -> FilterField.entries.toSet()
            ItemKind.ARTIST -> setOf(FilterField.FAVOURITES, FilterField.RATING, FilterField.DOWNLOADED)
            ItemKind.PLAYLIST -> setOf(FilterField.DOWNLOADED)
        }
    }
}
