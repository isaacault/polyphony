package app.polyphony.ui.browse.album

import app.polyphony.core.domain.model.CatalogAlbumTrack
import app.polyphony.core.domain.model.LibraryAlbumTracklist
import app.polyphony.core.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumTracklistTest {

    private fun track(id: String, title: String, disc: Int? = 1) = Track(id = id, serverId = "s", title = title, discNumber = disc)
    private fun whole(vararg titles: String) = LibraryAlbumTracklist(
        "rg", titles.mapIndexed { i, t -> CatalogAlbumTrack(i + 1, 1, t, 1_000, "rec-$i") },
    )

    private fun shape(entries: List<AlbumEntry>) = entries.map {
        when (it) {
            is AlbumEntry.Have -> "have:" + it.track.title
            is AlbumEntry.Missing -> "missing:" + it.track.title
        }
    }

    @Test
    fun `a single shows in its place among the rest of its album`() {
        val entries = AlbumTracklist.entries(listOf(track("t", "Visit Croatia")),
            whole("Song of Gratitude", "Visit Croatia", "Be My Lover"), "Alabaster dePlume", "To Cy & Lee")
        assertEquals(listOf("missing:Song of Gratitude", "have:Visit Croatia", "missing:Be My Lover"), shape(entries))
        val missing = entries.first() as AlbumEntry.Missing
        assertEquals("rec-0", missing.track.recordingId)
        assertEquals("rg", missing.track.albumReleaseGroupId)
        assertEquals("Alabaster dePlume", missing.track.artist)
    }

    @Test
    fun `titles match without featured artists or case`() {
        val entries = AlbumTracklist.entries(listOf(track("t", "visit croatia (feat. Someone)")), whole("Visit Croatia", "B"), null, null)
        assertEquals(listOf("have:visit croatia (feat. Someone)", "missing:B"), shape(entries))
    }

    @Test
    fun `a whole album in the library is just the library's songs`() {
        val lib = listOf(track("1", "A"), track("2", "B"))
        assertEquals(listOf("have:A", "have:B"), shape(AlbumTracklist.entries(lib, whole("A", "B"), null, null)))
        assertEquals(listOf("have:A", "have:B"), shape(AlbumTracklist.entries(lib, null, null, null)))
    }

    @Test
    fun `a tracklist that matches nothing is the wrong one and isn't shown`() {
        val lib = listOf(track("1", "Something Else"))
        assertEquals(listOf("have:Something Else"), shape(AlbumTracklist.entries(lib, whole("A", "B"), null, null)))
    }

    @Test
    fun `a library song the tracklist doesn't name stays at the end`() {
        val lib = listOf(track("1", "A"), track("2", "Bonus"))
        assertEquals(listOf("have:A", "missing:B", "missing:C", "have:Bonus"), shape(AlbumTracklist.entries(lib, whole("A", "B", "C"), null, null)))
    }
}
