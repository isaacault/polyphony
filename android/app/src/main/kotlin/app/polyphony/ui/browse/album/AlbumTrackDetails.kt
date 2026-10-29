package app.polyphony.ui.browse.album

import app.polyphony.core.domain.model.Track
import app.polyphony.core.ui.components.qualityBadgeLabel

/** What an album's track rows say beyond the title: only what the album page doesn't already. */
object AlbumTrackDetails {

    /** The second line of a track row on an album page. */
    sealed interface Credit {
        /** Artists featured on this track besides the album's artist: "ft. Future". */
        data class Featuring(val names: String) : Credit

        /** A track by someone else entirely (a compilation): its own artist. */
        data class Other(val artist: String) : Credit
    }

    // What joins a featured artist to the main one in a track's artist tag.
    private const val JOINER = """(?:\s|,|;|/|&|\+|\bx\b|\b(?:feat|ft)\b\.?|\bfeaturing\b|\bwith\b|\band\b)"""
    private val joiners = Regex("^$JOINER+", RegexOption.IGNORE_CASE)
    private val trailing = Regex("$JOINER+$", RegexOption.IGNORE_CASE)

    /**
     * Nothing when the track is by the album's artist alone, or when its title already names who's
     * featured ("Jimmy Cooks (feat. 21 Savage)"); the featured artists when the album's artist is
     * on it with others; the track's own artist on a compilation.
     */
    fun credit(track: Track, albumArtist: String?): Credit? {
        val artist = track.artistName?.trim().orEmpty()
        if (artist.isEmpty()) return null
        val main = albumArtist?.trim().orEmpty()
        if (main.isEmpty() || main.equals("Various Artists", ignoreCase = true)) return Credit.Other(artist)
        if (artist.equals(main, ignoreCase = true)) return null
        val at = artist.indexOf(main, ignoreCase = true)
        if (at < 0) return Credit.Other(artist)
        val extra = (artist.substring(0, at) + " " + artist.substring(at + main.length))
            .replace(joiners, "").replace(trailing, "").replace(Regex("""\s+"""), " ").trim()
        if (extra.isEmpty() || track.title.contains(extra, ignoreCase = true)) return null
        return Credit.Featuring(extra)
    }

    /** The album's quality: the most common lossless label among its tracks (FLAC, HI-RES…). */
    fun albumQuality(tracks: List<Track>): String? =
        tracks.mapNotNull(::qualityBadgeLabel).groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    /**
     * A track's own quality chip, only where it differs from the album's. On a HI-RES album a
     * lossless track at CD resolution says "16-BIT": its container ("FLAC") read as if the rest
     * weren't FLAC (We All Speak in Poems, one 16-bit track among 24-bit ones).
     */
    fun trackQuality(track: Track, albumQuality: String?): String? {
        val label = qualityBadgeLabel(track)?.takeIf { it != albumQuality } ?: return null
        return if (albumQuality == HI_RES && track.isLossless) "${track.bitDepth ?: 16}-BIT" else label
    }

    private const val HI_RES = "HI-RES"
}
