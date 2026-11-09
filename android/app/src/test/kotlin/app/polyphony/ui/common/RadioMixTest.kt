package app.polyphony.ui.common

import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.CatalogTrack
import app.polyphony.core.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioMixTest {
    private fun have(id: String, title: String = id) = Track(id = id, serverId = "s", title = title, durationMs = 200_000)
    private fun fetch(rec: String, title: String = rec) = CatalogTrack(
        key = "k$rec", title = title, artist = null, durationMs = 200_000, albumTitle = null, albumReleaseGroupId = "rg",
        availability = Availability.Requestable, recordingId = rec,
    )

    private val a = RadioMix.Artist("A", library = listOf(have("a1"), have("a2"), have("a3")), catalog = listOf(fetch("ra1")))
    private val b = RadioMix.Artist("B", library = listOf(have("b1")), catalog = listOf(fetch("rb1"), fetch("rb2")))
    private val c = RadioMix.Artist("C", catalog = listOf(fetch("rc1")))

    private fun keys(batch: RadioMix.Batch) = batch.picks.map { it.key }

    @Test fun `the library first, artists taking turns`() {
        val batch = RadioMix.batch(listOf(a, b, c), emptySet(), size = 4, maxFetch = 3)
        assertEquals(listOf("a1", "b1", "a2", "a3"), keys(batch))
    }

    @Test fun `then a few songs to fetch, never more than asked`() {
        val batch = RadioMix.batch(listOf(a, b, c), emptySet(), size = 10, maxFetch = 2)
        val fetched = batch.picks.filterIsInstance<RadioMix.Pick.Fetch>()
        assertEquals(4, batch.picks.count { it is RadioMix.Pick.Have })
        assertEquals(2, fetched.size)
        assertTrue("library picks come before fetched ones", batch.picks.take(4).all { it is RadioMix.Pick.Have })
    }

    @Test fun `nothing is fetched without Play now`() {
        val batch = RadioMix.batch(listOf(a, b, c), emptySet(), size = 10, maxFetch = 3, canFetch = false)
        assertTrue(batch.picks.all { it is RadioMix.Pick.Have })
    }

    @Test fun `what's been played isn't played again, nor its catalogue copy`() {
        val withCopy = RadioMix.Artist("B", library = listOf(have("b1", title = "Song")), catalog = listOf(fetch("rb9", title = "song ")))
        val played = setOf("a1", "b1", RadioMix.sameSong("Song", "B"))
        val batch = RadioMix.batch(listOf(a, withCopy), played, size = 10, maxFetch = 3)
        assertTrue(batch.picks.none { it.key == "a1" || it.key == "b1" || it.key == "rec:rb9" })
    }

    @Test fun `the next batch starts with the next artist`() {
        val first = RadioMix.batch(listOf(a, b, c), emptySet(), size = 1, maxFetch = 0)
        assertEquals(listOf("a1"), keys(first))
        val second = RadioMix.batch(listOf(a, b, c), setOf("a1"), size = 1, maxFetch = 0, fromArtist = first.nextArtist)
        assertEquals(listOf("b1"), keys(second))
    }

    @Test fun `no artist twice in a row where another can go between`() {
        val picks = listOf("A", "A", "A", "B", "C").mapIndexed { i, artist -> RadioMix.Pick.Have(have("t$i"), artist) }
        val spread = RadioMix.spreadArtists(picks).map { it.artist }
        assertEquals(listOf("A", "B", "A", "C", "A"), spread)
        assertEquals("one artist alone stays as it is", listOf("A", "A"),
            RadioMix.spreadArtists(picks.take(2)).map { it.artist })
    }

    @Test fun `the first fetched song isn't by whoever played last`() {
        val picks = listOf("A", "B").mapIndexed { i, artist -> RadioMix.Pick.Have(have("f$i"), artist) }
        assertEquals(listOf("B", "A"), RadioMix.spreadArtists(picks, after = "A").map { it.artist })
    }

    @Test fun `a song without a recording can't be fetched`() {
        val noRecording = RadioMix.Artist("D", catalog = listOf(fetch("x").copy(recordingId = null)))
        assertTrue(RadioMix.batch(listOf(noRecording), emptySet(), size = 3, maxFetch = 3).picks.isEmpty())
    }

    @Test fun `no artists, no songs`() {
        assertTrue(RadioMix.batch(emptyList(), emptySet(), size = 3, maxFetch = 3).picks.isEmpty())
    }
}
