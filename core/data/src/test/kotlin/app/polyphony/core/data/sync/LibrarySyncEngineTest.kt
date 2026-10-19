package app.polyphony.core.data.sync

import app.polyphony.core.domain.model.AlbumListType
import app.polyphony.core.domain.model.Playlist
import app.polyphony.core.domain.model.StarredContent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySyncEngineTest {

    private val engine = LibrarySyncEngine(pageSize = 500, concurrency = 4)

    @Test
    fun `full sync pages albums and tracks and deletes stale rows`() = runTest {
        val provider = FakeProvider(albumCount = 1_200, tracksPerAlbum = 2, playlists = listOf(Playlist("p1", SERVER_ID, "Mix")))
        val sink = RecordingSink()
        val phases = mutableListOf<String>()

        val summary = engine.run(provider, sink, generation = 7L, full = true) { phase, _ -> phases += phase }

        assertEquals(1, provider.pings)
        assertEquals(1_200, sink.albums.size)
        assertEquals(listOf(0, 500, 1_000), provider.albumCalls.filter { it.first == AlbumListType.ALPHABETICAL_BY_NAME }.map { it.second })
        assertEquals(2_400, sink.tracks.size)
        assertEquals(listOf(500, 500, 500, 500, 400), sink.trackWrites)
        assertEquals(setOf(7L), sink.generations)
        assertEquals(7L, sink.staleDeletedAt)
        assertEquals(listOf("p1"), sink.keptPlaylistIds?.toList())
        assertEquals(3, sink.playlists["p1"]?.size)
        assertNotNull(sink.starred)
        assertEquals(1, sink.genres.size)
        assertTrue(summary.full)
        assertFalse(summary.usedAlbumWalk)
        assertEquals(2_400, summary.tracks)
        assertTrue(phases.any { it.startsWith("Albums") })
        assertTrue(phases.any { it.startsWith("Tracks") })
        assertEquals("Done", phases.last())
    }

    @Test
    fun `falls back to walking albums when track listing is unsupported`() = runTest {
        val provider = FakeProvider(albumCount = 50, tracksPerAlbum = 3, supportsAllTracks = false)
        val sink = RecordingSink()

        val summary = engine.run(provider, sink, generation = 1L, full = true) { _, _ -> }

        assertTrue(summary.usedAlbumWalk)
        assertEquals(50, provider.albumDetailCalls.size)
        assertEquals(150, sink.tracks.size)
        assertEquals(1L, sink.staleDeletedAt)
    }

    @Test
    fun `progress stays within 0 and 1 and is monotonic enough`() = runTest {
        val provider = FakeProvider(albumCount = 20, tracksPerAlbum = 1)
        val sink = RecordingSink()
        val values = mutableListOf<Float>()
        engine.run(provider, sink, generation = 1L, full = true) { _, p -> p?.let(values::add) }
        assertTrue(values.all { it in 0f..1f })
        assertEquals(1f, values.last())
    }

    @Test
    fun `incremental sync only fetches tracks for new or changed albums`() = runTest {
        val provider = FakeProvider(albumCount = 10, tracksPerAlbum = 2)
        // Albums 1..8 already cached with matching counts; al9 missing, al10 has a stale count.
        val cached = (1..8).associate { "al$it" to 2 } + ("al10" to 1)
        val sink = RecordingSink(cached)

        val summary = engine.run(provider, sink, generation = 3L, full = false) { _, _ -> }

        assertFalse(summary.full)
        assertEquals(setOf("al9", "al10"), provider.albumDetailCalls.toSet())
        assertEquals(4, sink.tracks.size)
        assertNull(sink.staleDeletedAt)
        assertEquals(10, sink.albums.size)
        assertTrue(provider.albumCalls.all { it.first == AlbumListType.RECENTLY_ADDED })
    }

    @Test
    fun `starred content is applied even when empty`() = runTest {
        val provider = FakeProvider(albumCount = 1, starred = StarredContent())
        val sink = RecordingSink()
        engine.run(provider, sink, generation = 2L, full = true) { _, _ -> }
        assertNotNull(sink.starred)
    }
}
