package app.polyphony.core.dlna.engine

import app.polyphony.core.dlna.service.TransportState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class DlnaPlaybackEngineTest {

    private fun TestScope.fixture(
        durations: Map<String, Long> = mapOf("a" to 10_000, "b" to 8_000, "c" to 6_000),
        configure: FakeTransport.() -> Unit = {},
    ): Pair<FakeTransport, DlnaPlaybackEngine<String>> {
        val clock = { testScheduler.currentTime }
        val transport = FakeTransport(clock).apply { this.durations.putAll(durations); configure() }
        val engine = DlnaPlaybackEngine<String>(
            transport = transport,
            resolver = { id -> ResolvedStream(url = id, mimeType = "audio/flac", durationMs = durations[id], title = "Track $id") },
            scope = backgroundScope,
            clock = clock,
            random = Random(7),
        )
        return transport to engine
    }

    private suspend fun DlnaPlaybackEngine<String>.start(vararg ids: String, play: Boolean = true) {
        setMediaItems(ids.toList()).join()
        prepare().join()
        if (play) setPlayWhenReady(true).join()
    }

    @Test
    fun `prepare and play loads the current item and tracks position`() = runTest {
        val (t, e) = fixture()
        e.start("a", "b")
        val s = e.state.value
        assertEquals(EnginePlaybackState.READY, s.playbackState)
        assertTrue(s.playWhenReady)
        assertEquals(0, s.currentIndex)
        assertEquals(listOf("setUri:a", "play"), t.calls.filter { it.startsWith("setUri") || it == "play" })
        advanceTimeBy(3_100)
        assertTrue("position should advance", e.state.value.positionAt(testScheduler.currentTime) in 2_900..3_300)
        assertEquals(10_000L, e.state.value.durationMs)
    }

    @Test
    fun `end of track advances via stop detection when SetNext is unsupported`() = runTest {
        val (t, e) = fixture { supportsSetNext = false }
        e.start("a", "b")
        advanceTimeBy(12_000)
        assertEquals(1, e.state.value.currentIndex)
        assertEquals(EnginePlaybackState.READY, e.state.value.playbackState)
        assertTrue(t.calls.contains("setUri:b"))
        assertEquals("only one SetNext attempt before giving up", 1, t.setNextAttempts)
        assertTrue(t.calls.none { it.startsWith("setNext") })
    }

    @Test
    fun `SetNext hand-off is detected without re-cueing`() = runTest {
        val (t, e) = fixture()
        e.start("a", "b", "c")
        advanceTimeBy(2_500)
        assertTrue("next track should be armed", t.calls.contains("setNext:b"))
        advanceTimeBy(9_000) // a (10 s) ends, renderer flips to b by itself
        val s = e.state.value
        assertEquals(1, s.currentIndex)
        assertEquals(EnginePlaybackState.READY, s.playbackState)
        assertEquals("b must not be re-set via SetAVTransportURI", 1, t.calls.count { it.startsWith("setUri") })
        advanceTimeBy(2_500)
        assertTrue("c should now be armed", t.calls.contains("setNext:c"))
    }

    @Test
    fun `queue ends after the last track when repeat is off`() = runTest {
        val (_, e) = fixture { supportsSetNext = false }
        e.start("c")
        advanceTimeBy(9_000)
        val s = e.state.value
        assertEquals(EnginePlaybackState.ENDED, s.playbackState)
        assertEquals(6_000L, s.positionMs)
        assertTrue(s.playWhenReady)
    }

    @Test
    fun `pause freezes position and seek moves it`() = runTest {
        val (t, e) = fixture()
        e.start("a")
        advanceTimeBy(2_000)
        e.setPlayWhenReady(false).join()
        assertTrue(t.calls.contains("pause"))
        val frozen = e.state.value.positionMs
        assertFalse(e.state.value.playWhenReady)
        advanceTimeBy(3_000)
        assertEquals(frozen, e.state.value.positionAt(testScheduler.currentTime))
        e.seekTo(0, 7_000).join()
        assertTrue(t.calls.contains("seek:7000"))
        assertEquals(7_000L, e.state.value.positionMs)
        e.setPlayWhenReady(true).join()
        assertTrue(e.state.value.isPlayingNow)
    }

    @Test
    fun `renderers without Pause are stopped and re-cued on resume`() = runTest {
        val (t, e) = fixture { supportsPause = false }
        e.start("a")
        advanceTimeBy(2_000)
        e.setPlayWhenReady(false).join()
        assertEquals(TransportState.STOPPED, t.state)
        assertFalse(e.state.value.playWhenReady)
        e.setPlayWhenReady(true).join()
        assertEquals(2, t.calls.count { it == "setUri:a" })
        assertTrue(t.calls.any { it.startsWith("seek:") })
        assertTrue(e.state.value.isPlayingNow)
    }

    @Test
    fun `repeat one replays the same track`() = runTest {
        val (t, e) = fixture { supportsSetNext = false }
        e.setRepeatMode(EngineRepeatMode.ONE).join()
        e.start("c", "a")
        advanceTimeBy(9_000)
        assertEquals(0, e.state.value.currentIndex)
        assertEquals(2, t.calls.count { it == "setUri:c" })
    }

    @Test
    fun `repeat all wraps to the first track`() = runTest {
        val (t, e) = fixture { supportsSetNext = false }
        e.setRepeatMode(EngineRepeatMode.ALL).join()
        e.start("c", "b")
        e.next().join()
        assertEquals(1, e.state.value.currentIndex)
        advanceTimeBy(11_000) // b (8 s) ends → wraps to c
        assertEquals(0, e.state.value.currentIndex)
        assertEquals(2, t.calls.count { it == "setUri:c" })
    }

    @Test
    fun `explicit next and previous respect shuffle order`() = runTest {
        val (_, e) = fixture()
        e.start("a", "b", "c", play = false)
        e.setShuffleModeEnabled(true).join()
        val order = e.state.value.shuffleOrder
        assertEquals(setOf(0, 1, 2), order.toSet())
        assertEquals(0, order.first())
        e.next().join()
        assertEquals(order[1], e.state.value.currentIndex)
        e.next().join()
        assertEquals(order[2], e.state.value.currentIndex)
        assertNull("no wrap when repeat is off", e.state.value.nextIndex())
        e.previous().join()
        assertEquals(order[1], e.state.value.currentIndex)
    }

    @Test
    fun `removing the current item plays the following one`() = runTest {
        val (t, e) = fixture()
        e.start("a", "b", "c")
        e.removeMediaItems(0, 1).join()
        val s = e.state.value
        assertEquals(listOf("b", "c"), s.items.map { it.item })
        assertEquals(0, s.currentIndex)
        assertTrue(t.calls.contains("setUri:b"))
        e.removeMediaItems(0, 2).join()
        assertEquals(EnginePlaybackState.ENDED, e.state.value.playbackState)
        assertEquals(-1, e.state.value.currentIndex)
    }

    @Test
    fun `adding and moving items keeps the current item stable`() = runTest {
        val (_, e) = fixture()
        e.start("a", "b")
        e.addMediaItems(0, listOf("c")).join()
        assertEquals(listOf("c", "a", "b"), e.state.value.items.map { it.item })
        assertEquals(1, e.state.value.currentIndex)
        e.moveMediaItems(1, 2, 2).join()
        assertEquals(listOf("c", "b", "a"), e.state.value.items.map { it.item })
        assertEquals(2, e.state.value.currentIndex)
        assertEquals(EnginePlaybackState.READY, e.state.value.playbackState)
    }

    @Test
    fun `pause and play on the renderer itself are mirrored`() = runTest {
        val (t, e) = fixture()
        e.start("a")
        advanceTimeBy(2_500)
        t.externalPause()
        advanceTimeBy(1_100)
        assertFalse(e.state.value.playWhenReady)
        assertEquals(EnginePlaybackState.READY, e.state.value.playbackState)
        t.externalPlay()
        advanceTimeBy(1_100)
        assertTrue(e.state.value.playWhenReady)
    }

    @Test
    fun `stop resets to idle and release ignores later commands`() = runTest {
        val (t, e) = fixture()
        e.start("a")
        e.stop().join()
        assertEquals(EnginePlaybackState.IDLE, e.state.value.playbackState)
        assertTrue(t.calls.contains("stop"))
        assertEquals(1, e.state.value.items.size)
        e.release().join()
        assertTrue(e.state.value.released)
        val callsBefore = t.calls.size
        e.setPlayWhenReady(true).join()
        assertEquals(callsBefore, t.calls.size)
    }

    @Test
    fun `an unreachable renderer surfaces an error after repeated poll failures`() = runTest {
        val (t, e) = fixture()
        e.start("a")
        t.failAll = true
        advanceTimeBy(6_500)
        val s = e.state.value
        assertNotNull(s.error)
        assertEquals(EnginePlaybackState.IDLE, s.playbackState)
    }

    @Test
    fun `a failing load reports an error instead of crashing`() = runTest {
        val (_, e) = fixture { failAll = true }
        e.setMediaItems(listOf("a")).join()
        e.prepare().join()
        assertNotNull(e.state.value.error)
        assertEquals(EnginePlaybackState.IDLE, e.state.value.playbackState)
    }

    @Test
    fun `volume is forwarded and clamped`() = runTest {
        val (t, e) = fixture()
        e.setInitialVolume(30)
        assertEquals(30, e.state.value.volumePercent)
        e.setVolume(140).join()
        assertEquals(100, t.volume)
        assertEquals(100, e.state.value.volumePercent)
    }
}
