package app.polyphony.ui.browse.album

import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.CatalogAlbumTrack
import app.polyphony.core.domain.model.CatalogTrack
import app.polyphony.core.domain.model.LibraryAlbumTracklist
import app.polyphony.core.domain.model.Track

/** One row of an album page: a song you have, or one of the album's you don't. */
sealed interface AlbumEntry {
    val disc: Int

    data class Have(val track: Track, override val disc: Int) : AlbumEntry

    /** Not in the library: Play now plays it, Request fetches it. */
    data class Missing(val track: CatalogTrack, override val disc: Int) : AlbumEntry
}

/**
 * A library album as the whole album it's of: a single song filed alone (To Cy & Lee for Visit
 * Croatia) shows among the rest of its album, in the album's order, the rest offered to play or
 * request. Songs match by title; a library song the tracklist doesn't name stays, at the end of
 * its disc. Without a tracklist, or one that matches none of the library's songs (the wrong
 * release), the page is the library's songs alone, as before.
 */
object AlbumTracklist {

    fun entries(library: List<Track>, whole: LibraryAlbumTracklist?, artist: String?, albumTitle: String?,
                artworkUrl: String? = null): List<AlbumEntry> {
        val own = library.map { AlbumEntry.Have(it, it.discNumber ?: 1) }
        if (whole == null || whole.tracks.isEmpty() || whole.tracks.size <= library.size) return own
        val byTitle = library.groupBy { key(it.title) }.mapValues { it.value.toMutableList() }
        val used = HashSet<String>()
        val out = whole.tracks.map { t ->
            val have = byTitle[key(t.title)]?.removeFirstOrNull()
            if (have != null) {
                used += have.id
                AlbumEntry.Have(have, t.discNumber)
            } else {
                AlbumEntry.Missing(t.asCatalogTrack(whole.releaseGroupId, artist, albumTitle, artworkUrl), t.discNumber)
            }
        }
        if (used.isEmpty() && library.isNotEmpty()) return own
        val rest = own.filter { it.track.id !in used }
        return (out + rest).sortedBy { it.disc }   // stable: album order within a disc, strays last
    }

    /** "Visit Croatia (feat. X)" and "visit croatia" are one song. */
    internal fun key(title: String): String =
        title.lowercase().replace(FEATURING, "").filter { it.isLetterOrDigit() }

    private val FEATURING = Regex("""\s*[(\[](?:feat|ft|featuring|with)\b[^)\]]*[)\]]""")

    private fun CatalogAlbumTrack.asCatalogTrack(releaseGroupId: String, artist: String?, albumTitle: String?, artworkUrl: String?) = CatalogTrack(
        key = recordingId?.let { "mb:rec:$it" } ?: "rg:$releaseGroupId:$discNumber:$position",
        title = title,
        artist = artist,
        durationMs = durationMs,
        albumTitle = albumTitle,
        albumReleaseGroupId = releaseGroupId,
        availability = Availability.Requestable,
        recordingId = recordingId,
        artworkUrl = artworkUrl,
    )
}
