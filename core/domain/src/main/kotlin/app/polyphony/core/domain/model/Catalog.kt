package app.polyphony.core.domain.model

/**
 * Where a search hit lives, as unified sources show it (#50): on the phone, on the server, or only
 * in a continuo server's catalogue.
 */
enum class SourceTier { DOWNLOADED, LIBRARY, EVERYTHING }

/** What can be done with a catalogue hit right now: continuo's `availability`. */
sealed interface Availability {
    /** In the server library; [libraryAlbumId] opens it (an album, or a track's album). */
    data class InLibrary(val libraryAlbumId: String?) : Availability

    /** Partly in the library: [have] of [expected] tracks. */
    data class Partial(val have: Int, val expected: Int, val libraryAlbumId: String?) : Availability

    /** Not in the library and not requested: can be requested. */
    data object Requestable : Availability

    /**
     * A request is under way. [state] is continuo's status: requested, searching, downloading,
     * importing, scanning or needs_attention. [stillLooking] means nobody has it yet; searching
     * continues on its own.
     */
    data class Requested(val requestId: Long?, val state: String, val stillLooking: Boolean = false) : Availability

    /** Browse-only (an artist): there is nothing to request as a whole. */
    data object CatalogOnly : Availability
}

/** An album from a continuo server's catalogue (MusicBrainz release group). */
data class CatalogAlbum(
    val key: String,
    val releaseGroupId: String?,
    val title: String,
    val artist: String?,
    val year: Int?,
    val albumType: String?,
    val artworkUrl: String?,
    val availability: Availability,
)

/** A track from the catalogue; requesting it requests its album ([albumReleaseGroupId]). */
data class CatalogTrack(
    val key: String,
    val title: String,
    val artist: String?,
    val durationMs: Long?,
    val albumTitle: String?,
    val albumReleaseGroupId: String?,
    val availability: Availability,
)

data class CatalogResults(
    val albums: List<CatalogAlbum> = emptyList(),
    val tracks: List<CatalogTrack> = emptyList(),
    /** False when the server answered but its catalogue (Lidarr / MusicBrainz) was unreachable. */
    val catalogAvailable: Boolean = true,
)

sealed interface RequestOutcome {
    data class Requested(val requestId: Long) : RequestOutcome
    data class AlreadyRequested(val requestId: Long?) : RequestOutcome
    data class InLibrary(val libraryAlbumId: String?) : RequestOutcome
}
