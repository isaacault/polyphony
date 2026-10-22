package app.polyphony.ui.browse.catalog

import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.CatalogAlbum
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** The release types an artist's discography is grouped by, in page order. */
enum class ReleaseType { ALBUM, EP, SINGLE, OTHER }

data class DiscographyGroup(val type: ReleaseType, val albums: ImmutableList<CatalogAlbum>)

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
            .filterNot { it.availability is Availability.InLibrary || it.availability is Availability.Partial }
            .groupBy { typeOf(it.albumType) }
            .toSortedMap()
            .map { (type, list) -> DiscographyGroup(type, list.sortedByDescending { it.year ?: 0 }.toImmutableList()) }

    /** Library albums the catalogue only partly matches, by library album id. */
    fun partials(albums: List<CatalogAlbum>): Map<String, Availability.Partial> =
        albums.mapNotNull { a -> (a.availability as? Availability.Partial)?.let { p -> p.libraryAlbumId?.let { it to p } } }.toMap()
}
