package app.polyphony.core.playback.stream

import app.polyphony.core.domain.transcode.NetworkKind
import app.polyphony.core.domain.transcode.NetworkState
import app.polyphony.core.playback.stream.PrefetchPlan.Upcoming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrefetchPlanTest {

    private val wifi = NetworkState(NetworkKind.WIFI, isMetered = false)
    private val mobile = NetworkState(NetworkKind.CELLULAR, isMetered = true)
    private val offline = NetworkState(NetworkKind.NONE, isMetered = false)

    private fun songs(vararg minutes: Int) = minutes.mapIndexed { i, m -> Upcoming("t$i", m * 60_000L) }

    @Test
    fun `on wi-fi it keeps about twenty minutes ahead`() {
        // 4-minute songs: five reach 20 minutes.
        assertEquals(listOf("t0", "t1", "t2", "t3", "t4"), PrefetchPlan.pick(songs(4, 4, 4, 4, 4, 4, 4), PrefetchPlan.WIFI))
    }

    @Test
    fun `long songs still get at least two, short ones stop at ten`() {
        assertEquals(listOf("t0", "t1"), PrefetchPlan.pick(songs(25, 25, 25), PrefetchPlan.WIFI))
        assertEquals(10, PrefetchPlan.pick(songs(*IntArray(15) { 1 }), PrefetchPlan.WIFI).size)
    }

    @Test
    fun `on mobile data less, and nothing when that's turned off or offline`() {
        assertEquals(listOf("t0", "t1"), PrefetchPlan.pick(songs(4, 4, 4, 4), PrefetchPlan.MOBILE))
        assertEquals(PrefetchPlan.MOBILE, PrefetchPlan.budget(mobile, enabled = true, onMobileData = true))
        assertNull(PrefetchPlan.budget(mobile, enabled = true, onMobileData = false))
        assertNull(PrefetchPlan.budget(offline, enabled = true, onMobileData = true))
        assertNull(PrefetchPlan.budget(wifi, enabled = false, onMobileData = true))
        assertEquals(PrefetchPlan.WIFI, PrefetchPlan.budget(wifi, enabled = true, onMobileData = false))
    }

    @Test
    fun `downloaded songs count towards the time ahead but aren't fetched`() {
        val queue = listOf(Upcoming("d0", 10 * 60_000L, onDevice = true), Upcoming("t1", 6 * 60_000L),
                           Upcoming("p2", 3 * 60_000L, fetchable = false), Upcoming("t3", 4 * 60_000L), Upcoming("t4", 4 * 60_000L))
        assertEquals(listOf("t1", "t3"), PrefetchPlan.pick(queue, PrefetchPlan.WIFI))
    }
}
