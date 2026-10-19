package app.polyphony.core.data.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineIndexTest {

    private val refs = listOf(
        OfflineTrackRef("t1", albumId = "a1", artistId = "ar1", genre = "Rock"),
        OfflineTrackRef("t2", albumId = "a1", artistId = "ar1", genre = "Rock"),
        OfflineTrackRef("t3", albumId = "a2", artistId = "ar2", genre = ""),
        OfflineTrackRef("t9", albumId = "a9", artistId = "ar9", genre = "Jazz"), // not downloaded
    )

    @Test
    fun `build collects collections of downloaded tracks only`() {
        val index = OfflineIndex.build(setOf("t1", "t3"), refs, listOf("p1"))
        assertEquals(setOf("t1", "t3"), index.trackIds)
        assertEquals(setOf("a1", "a2"), index.albumIds)
        assertEquals(setOf("ar1", "ar2"), index.artistIds)
        assertEquals(setOf("p1"), index.playlistIds)
        assertEquals(setOf("Rock"), index.genres)
    }

    @Test
    fun `empty track set yields the empty index`() {
        assertSame(OfflineIndex.EMPTY, OfflineIndex.build(emptySet(), refs, listOf("p1")))
    }

    @Test
    fun `null index means no filtering`() {
        val list = listOf("x", "y")
        assertSame(list, OfflineFilter.apply(list, null, { it.trackIds }) { it })
    }

    @Test
    fun `filter keeps only allowed keys`() {
        val index = OfflineIndex.build(setOf("t1"), refs, emptyList())
        val albums = listOf("a1", "a2", "a9")
        assertEquals(listOf("a1"), OfflineFilter.apply(albums, index, { it.albumIds }) { it })
        assertTrue(OfflineFilter.apply(listOf("p1"), index, { it.playlistIds }) { it }.isEmpty())
    }

    @Test
    fun `chunk size stays below sqlite variable limit`() {
        assertTrue(OfflineFilter.IN_CHUNK < 999)
    }
}
