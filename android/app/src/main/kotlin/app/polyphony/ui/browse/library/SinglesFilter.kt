package app.polyphony.ui.browse.library

import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Artist

/** Which artists Library's Artists shows without Single songs on. */
object SinglesFilter {
    /**
     * [artists] without those whose every album in the library holds single songs ([singleIds]):
     * an artist you have a full album of stays; one you only have a song by goes. An artist with
     * no albums of their own (on others' compilations) stays.
     */
    fun artistsWithAlbums(artists: List<Artist>, albums: List<Album>, singleIds: Set<String>): List<Artist> {
        val byArtist = albums.groupBy { it.artistId }
        return artists.filter { artist ->
            val theirs = byArtist[artist.id].orEmpty()
            theirs.isEmpty() || theirs.any { it.id !in singleIds }
        }
    }
}
