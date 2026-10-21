package app.polyphony.ui.browse.search

import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.CatalogAlbum
import app.polyphony.core.domain.model.CatalogResults
import app.polyphony.core.domain.model.CatalogTrack

/**
 * Search scopes (#50), offered only for a continuo-linked server. ALL adds the Everything tier to
 * the library results; LIBRARY is today's search; DOWNLOADED keeps only what is on the phone.
 */
enum class SearchScope { ALL, LIBRARY, DOWNLOADED }

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

    /** True when there's something to request: a release group and nothing under way or owned. */
    fun canRequest(availability: Availability, releaseGroupId: String?): Boolean =
        releaseGroupId != null && availability == Availability.Requestable

    private fun Availability.isInLibrary(): Boolean =
        this is Availability.InLibrary || this is Availability.Partial
}
