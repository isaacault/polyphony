package app.polyphony.core.playback.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayNowLinkCacheTest {
    private var now = 1_000_000L
    private val cache = PlayNowLinkCache(now = { now }, keepMs = 60_000L, retryMs = 5_000L)

    @Test
    fun `a link is kept until it gets old`() {
        cache.put("s1", "http://lan/v1/play-now/s1/audio?sig=a")
        now += 59_000
        assertEquals("http://lan/v1/play-now/s1/audio?sig=a", cache.fresh("s1"))
        now += 1_000
        assertNull("an old link is fetched again, so a speaker never gets one about to lapse", cache.fresh("s1"))
    }

    @Test
    fun `a refused stream isn't asked for again at once`() {
        cache.failed("s1")
        assertTrue(cache.recentlyFailed("s1"))
        now += 5_000
        assertFalse(cache.recentlyFailed("s1"))
    }

    @Test
    fun `a link that comes clears the refusal`() {
        cache.failed("s1")
        cache.put("s1", "http://lan/a")
        assertFalse(cache.recentlyFailed("s1"))
        assertEquals("http://lan/a", cache.fresh("s1"))
    }

    @Test
    fun `the oldest link makes room when the cache is full`() {
        repeat(256) { i -> cache.put("s$i", "u$i"); now += 1 }
        cache.put("new", "u-new")
        assertNull(cache.fresh("s0"))
        assertEquals("u1", cache.fresh("s1"))
        assertEquals("u-new", cache.fresh("new"))
    }
}
