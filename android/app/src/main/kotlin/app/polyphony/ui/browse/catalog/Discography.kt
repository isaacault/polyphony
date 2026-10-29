package app.polyphony.ui.browse.catalog

import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.CatalogAlbum
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** The release types an artist's discography is grouped by, in page order. */
enum class ReleaseType { ALBUM, EP, SINGLE, OTHER }

data class DiscographyGroup(val type: ReleaseType, val albums: ImmutableList<CatalogAlbum>)

/** One card in an artist's merged discography: your album, or one from the catalogue. */
sealed interface DiscographyEntry {
    val key: String
    val year: Int?

    data class Owned(val album: Album) : DiscographyEntry {
        override val key: String get() = "o-" + album.id
        override val year: Int? get() = album.year
    }

    data class Missing(val album: CatalogAlbum) : DiscographyEntry {
        override val key: String get() = "m-" + album.key
        override val year: Int? get() = album.year
    }
}

data class MergedGroup(val type: ReleaseType, val entries: ImmutableList<DiscographyEntry>)

/**
 * An artist's discography from a continuo server's catalogue (#144), as artist pages show it: the
 * releases that aren't in the library, grouped Albums, EPs, Singles, newest first in each.
 */
object Discography {

    fun typeOf(albumType: String?): ReleaseType = when (albumType?.lowercase()) {
        "album" -> ReleaseType.ALBUM
        "ep" -> ReleaseType.EP
        "single" -> ReleaseType.SINGLE
        else -> ReleaseType.OTHER
    }

    /**
     * The releases to offer, with this screen's [requested] applied (a request made here shows at
     * once). Library albums are left out: the page already lists them, partial ones with a badge.
     */
    fun missing(albums: List<CatalogAlbum>, requested: Map<String, Availability>): List<DiscographyGroup> =
        albums
            .map { a -> a.releaseGroupId?.let(requested::get)?.let { a.copy(availability = it) } ?: a }
            .filterNot { it.availability is Availability.InLibrary || (it.availability as? Availability.Partial)?.requestable == false }
            .groupBy { typeOf(it.albumType) }
            .toSortedMap()
            .map { (type, list) -> DiscographyGroup(type, list.sortedByDescending { it.year ?: 0 }.toImmutableList()) }

    /**
     * A library artist's albums and the rest of their discography as one list, grouped Albums,
     * EPs, Singles, newest first: your copy where you have one (continuo marks it in_library or
     * partial with its library id), the catalogue's release where you don't. Library albums the
     * catalogue doesn't list (compilations, live albums, untagged rips) join the Albums. Empty
     * without a catalogue, so the page keeps its library-only grid.
     */
    fun merged(library: List<Album>, catalog: List<CatalogAlbum>, requested: Map<String, Availability>): List<MergedGroup> {
        if (catalog.isEmpty()) return emptyList()
        val byId = library.associateBy { it.id }
        val placed = mutableSetOf<String>()
        val entries = mutableListOf<Pair<ReleaseType, DiscographyEntry>>()
        for (c in catalog) {
            val album = c.releaseGroupId?.let(requested::get)?.let { c.copy(availability = it) } ?: c
            val owned = libraryId(album.availability)?.let(byId::get)
            when {
                owned == null -> entries += typeOf(album.albumType) to DiscographyEntry.Missing(album)
                placed.add(owned.id) -> entries += typeOf(album.albumType) to DiscographyEntry.Owned(owned)
                // A second release the server matched to the same library album (a "0" taken for
                // "0%"): one library album can't be both, so this one is offered, never dropped.
                else -> entries += typeOf(album.albumType) to DiscographyEntry.Missing(
                    if (album.releaseGroupId != null) album.copy(availability = Availability.Requestable) else album,
                )
            }
        }
        library.filter { it.id !in placed }.forEach { entries += ReleaseType.ALBUM to DiscographyEntry.Owned(it) }
        return entries.groupBy({ it.first }, { it.second }).toSortedMap().map { (type, list) ->
            MergedGroup(type, list.sortedWith(compareByDescending<DiscographyEntry> { it.year ?: 0 }.thenBy { it !is DiscographyEntry.Owned }).toImmutableList())
        }
    }

    /** The library album a catalogue release is, when it's (partly) in the library. */
    fun libraryId(availability: Availability): String? = when (availability) {
        is Availability.InLibrary -> availability.libraryAlbumId
        // Only single songs of it: offered as the album to request, not as your copy.
        is Availability.Partial -> availability.libraryAlbumId.takeUnless { availability.requestable }
        else -> null
    }

    /** Library albums the catalogue only partly matches, by library album id. */
    fun partials(albums: List<CatalogAlbum>): Map<String, Availability.Partial> =
        albums.mapNotNull { a -> (a.availability as? Availability.Partial)?.let { p -> p.libraryAlbumId?.let { it to p } } }.toMap()
}
