package app.polyphony.core.playback.crossfade

import app.polyphony.core.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadePlanTest {
    private fun track(id: String, album: String? = "al", disc: Int? = 1, number: Int? = null) =
        Track(id = id, serverId = "s", title = id, durationMs = 240_000, albumId = album, discNumber = disc, trackNumber = number)

    @Test fun `off, or nothing next, is no fade`() {
        assertEquals(0L, CrossfadePlan.fadeMs(0, false, track("a"), track("b", album = "x"), 240_000))
        assertEquals(0L, CrossfadePlan.fadeMs(6_000, false, track("a"), null, 240_000))
        assertEquals(0L, CrossfadePlan.fadeMs(6_000, false, track("a"), track("b"), -1))
    }

    @Test fun `the setting, but at most a third of the song`() {
        assertEquals(6_000L, CrossfadePlan.fadeMs(6_000, false, track("a"), track("b", album = "x"), 240_000))
        assertEquals(5_000L, CrossfadePlan.fadeMs(12_000, false, track("a"), track("b", album = "x"), 15_000))
        assertEquals("a two-second song isn't worth it", 0L, CrossfadePlan.fadeMs(12_000, false, track("a"), track("b", album = "x"), 2_000))
    }

    @Test fun `an album in order stays gapless when asked`() {
        val a3 = track("a3", number = 3)
        val a4 = track("a4", number = 4)
        assertEquals(0L, CrossfadePlan.fadeMs(6_000, true, a3, a4, 240_000))
        assertEquals("unless gapless albums is off", 6_000L, CrossfadePlan.fadeMs(6_000, false, a3, a4, 240_000))
        assertEquals("out of order, it fades", 6_000L, CrossfadePlan.fadeMs(6_000, true, a3, track("a7", number = 7), 240_000))
        assertEquals("another album fades", 6_000L, CrossfadePlan.fadeMs(6_000, true, a3, track("b1", album = "b", number = 4), 240_000))
    }

    @Test fun `consecutive across discs, and without numbers`() {
        assertTrue(CrossfadePlan.consecutiveOnAlbum(track("a", disc = 1, number = 12), track("b", disc = 2, number = 1)))
        assertFalse(CrossfadePlan.consecutiveOnAlbum(track("a", disc = 1, number = 12), track("b", disc = 2, number = 2)))
        assertTrue(CrossfadePlan.consecutiveOnAlbum(track("a"), track("b")))
        assertFalse(CrossfadePlan.consecutiveOnAlbum(track("a", album = null), track("b", album = null)))
    }

    @Test fun `equal-power curves keep the loudness level`() {
        assertEquals(0f, CrossfadePlan.fadeIn(0f), 1e-6f)
        assertEquals(1f, CrossfadePlan.fadeIn(1f), 1e-6f)
        assertEquals(1f, CrossfadePlan.fadeOut(0f), 1e-6f)
        assertEquals(0f, CrossfadePlan.fadeOut(1f), 1e-6f)
        for (i in 0..10) {
            val t = i / 10f
            val power = CrossfadePlan.fadeIn(t).let { it * it } + CrossfadePlan.fadeOut(t).let { it * it }
            assertEquals("t=$t", 1f, power, 1e-5f)
        }
        assertEquals("clamped", 1f, CrossfadePlan.fadeIn(3f), 1e-6f)
    }

    @Test fun `the second player gets ready ahead of the fade`() {
        assertEquals(234_000L, CrossfadePlan.fadeStartMs(240_000, 6_000))
        assertEquals(230_000L, CrossfadePlan.prepareAtMs(240_000, 6_000))
        assertEquals(0L, CrossfadePlan.prepareAtMs(5_000, 3_000))
    }
}
