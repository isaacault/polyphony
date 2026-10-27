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

    /**
     * Partly in the library: [have] of [expected] tracks. [requestable] when what's there is only
     * songs requested on their own: the whole album can still be requested.
     */
    data class Partial(val have: Int, val expected: Int, val libraryAlbumId: String?, val requestable: Boolean = false) : Availability

    /**
     * On the server but not in this account's own library (another account has it). Requesting
     * it adds it at once: nothing downloads. [storeAlbumId] is continuo's id for it.
     */
    data class OnServer(val storeAlbumId: String) : Availability

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

/**
 * An artist from the catalogue. Artists aren't requested as a whole; [musicBrainzId] opens their
 * discography, where each album can be.
 */
data class CatalogArtist(
    val key: String,
    val musicBrainzId: String?,
    val name: String,
    val artworkUrl: String?,
    val availability: Availability,
)

/**
 * A catalogue artist's own page (any artist, in the library or not): what a library artist page
 * shows, from the catalogue. The discography comes separately (RequestRepository.artistAlbums).
 */
data class CatalogArtistPage(
    val artist: CatalogArtist,
    val disambiguation: String?,
    val biography: String?,
    /** Where [biography] came from, to credit it (Wikipedia), when it's a page. */
    val biographyUrl: String?,
    val topTracks: List<CatalogTrack>,
    val similarArtists: List<CatalogArtist>,
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
    /** The song's MusicBrainz recording: a server with song requests gets just this song. */
    val recordingId: String? = null,
    /** Its album's cover, served by continuo. */
    val artworkUrl: String? = null,
) {
    /** What requests of this song are keyed by: the song itself, else its album. */
    val requestKeys: List<String> get() = listOfNotNull(recordingId, albumReleaseGroupId)
}

/** A catalogue album with its tracklist (one canonical release of the group), for its own page. */
data class CatalogAlbumDetail(
    val album: CatalogAlbum,
    val tracks: List<CatalogAlbumTrack>,
)

/** The whole album a library album is of (continuo's catalogue): for showing what it lacks. */
data class LibraryAlbumTracklist(
    val releaseGroupId: String,
    val tracks: List<CatalogAlbumTrack>,
)

data class CatalogAlbumTrack(
    val position: Int,
    val discNumber: Int,
    val title: String,
    val durationMs: Long?,
    /** The song's MusicBrainz recording: what Play now fetches. */
    val recordingId: String? = null,
)

data class CatalogResults(
    val artists: List<CatalogArtist> = emptyList(),
    val albums: List<CatalogAlbum> = emptyList(),
    val tracks: List<CatalogTrack> = emptyList(),
    /** False when the server answered but its catalogue (Lidarr / MusicBrainz) was unreachable. */
    val catalogAvailable: Boolean = true,
    /** Only the top few (a quick search, while typing): the full search has more. */
    val partial: Boolean = false,
)

/**
 * Home's discovery (continuo's /v1/discover): new releases from the artists in your library, and
 * songs from artists like them; each playable with Play now or requestable.
 */
data class Discovery(
    val newReleases: List<CatalogAlbum> = emptyList(),
    val forYou: List<CatalogTrack> = emptyList(),
)

sealed interface RequestOutcome {
    data class Requested(val requestId: Long) : RequestOutcome
    data class AlreadyRequested(val requestId: Long?) : RequestOutcome
    data class InLibrary(val libraryAlbumId: String?) : RequestOutcome

    /** It was on the server, so continuo put it straight into this account's library. */
    data object Added : RequestOutcome
}

/**
 * A request on a continuo server: one album that was asked for, and where it is now. [status] is
 * continuo's (pending, searching, downloading, importing, scanning, available, error,
 * needs_attention); there is no terminal failure, an album nobody has is "searching" with
 * [stillLooking] set.
 */
data class MusicRequest(
    val id: Long,
    val title: String,
    val artist: String?,
    val year: Int?,
    val artworkUrl: String?,
    val status: String,
    val stillLooking: Boolean,
    val pipeline: RequestPipeline?,
    val error: String?,
    val releaseGroupId: String?,
    /** The Navidrome album to open once [status] is available. */
    val libraryAlbumId: String?,
    val requestedBy: String?,
    val createdAt: String?,
)

/** continuo's live pipeline for a request: the stepper and its one-line explanation. */
data class RequestPipeline(
    val stages: List<String>,
    val stageIndex: Int,
    val percent: Float,
    val detail: String,
    /** soulseek | torrent, once known. */
    val source: String?,
    /** A Lidarr queue item is downloading (so it can be dropped). */
    val hasDownload: Boolean,
    val stalled: Boolean,
    /** Why it needs a look (import_not_visible, ambiguous_match, upgrade_detected), if it does. */
    val attentionReason: String?,
)

/** Live changes to requests, from continuo's event stream. */
sealed interface RequestEvent {
    data class Updated(
        val id: Long,
        val status: String,
        val stillLooking: Boolean,
        val pipeline: RequestPipeline?,
        val libraryAlbumId: String?,
    ) : RequestEvent

    /** Something changed that the event didn't describe (or the stream reconnected): reload. */
    data object Resync : RequestEvent
}

/** What can be done to a request from the app. */
enum class RequestAction { SEARCH_NOW, RETRY, DROP_DOWNLOAD, REMOVE }
