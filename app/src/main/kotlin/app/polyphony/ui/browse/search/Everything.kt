package app.polyphony.ui.browse.search

import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.CatalogAlbum
import app.polyphony.core.domain.model.CatalogResults
import app.polyphony.core.domain.model.CatalogTrack

/**
 * Search scopes (#50), offered only for a continuo-linked server. ALL blends catalogue hits into
 * the library sections; LIBRARY is the library alone; DOWNLOADED keeps only what is on the phone.
 */
enum class SearchScope { ALL, LIBRARY, DOWNLOADED }

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
    fun partials(results: CatalogResults?): Map<String, Availability.Partial> =
        results?.albums.orEmpty()
            .mapNotNull { a -> (a.availability as? Availability.Partial)?.let { p -> p.libraryAlbumId?.let { it to p } } }
            .toMap()

    /** True when there's something to request: a release group and nothing under way or owned. */
    fun canRequest(availability: Availability, releaseGroupId: String?): Boolean =
        releaseGroupId != null && availability == Availability.Requestable

    private fun Availability.isInLibrary(): Boolean =
        this is Availability.InLibrary || this is Availability.Partial
}
