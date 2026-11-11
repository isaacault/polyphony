package app.polyphony.core.domain.transcode

import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerOptions
import app.polyphony.core.domain.model.ServerType
import app.polyphony.core.domain.transcode.AdaptiveStreaming.Route
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveStreamingTest {
    private fun server(continuo: Boolean) = Server(
        id = "s", type = ServerType.SUBSONIC, name = "Home", baseUrl = "https://music-api.example",
        username = "isaac", credential = "x", deviceId = "d",
        options = if (continuo) mapOf(ServerOptions.CONTINUO to "true") else emptyMap(),
    )

    @Test
    fun `a continuo song streams as HLS`() {
        assertEquals(Route.HLS, AdaptiveStreaming.route(server(true), enabled = true, playNow = false, downloadedPlays = false))
    }

    @Test
    fun `everything else streams as before`() {
        assertEquals(Route.PROGRESSIVE, AdaptiveStreaming.route(server(false), true, false, false))
        assertEquals(Route.PROGRESSIVE, AdaptiveStreaming.route(null, true, false, false))
        assertEquals(Route.PROGRESSIVE, AdaptiveStreaming.route(server(true), enabled = false, playNow = false, downloadedPlays = false))
        assertEquals(Route.PROGRESSIVE, AdaptiveStreaming.route(server(true), true, playNow = true, downloadedPlays = false))
        assertEquals(Route.PROGRESSIVE, AdaptiveStreaming.route(server(true), true, playNow = false, downloadedPlays = true))
    }

    @Test
    fun `the streaming rules' profile bounds the quality`() {
        assertEquals(256, AdaptiveStreaming.ceilingKbps(TranscodeProfile.ORIGINAL))
        assertEquals(256, AdaptiveStreaming.ceilingKbps(TranscodeProfile.LOSSLESS_CAPPED))
        assertEquals(128, AdaptiveStreaming.ceilingKbps(TranscodeProfile.HIGH))      // 160 -> the rung below
        assertEquals(128, AdaptiveStreaming.ceilingKbps(TranscodeProfile.BALANCED))
        assertEquals(64, AdaptiveStreaming.ceilingKbps(TranscodeProfile.SAVER))
        assertEquals(256, AdaptiveStreaming.ceilingKbps(TranscodeProfile.CAST_SAFE))
        assertEquals(32, AdaptiveStreaming.ceilingKbps(TranscodeProfile("c", "Tiny", AudioCodec.OPUS, maxBitrateKbps = 24)))
    }

    @Test
    fun `a connection that keeps stalling drops to the lowest rung, whatever the rules allow`() {
        assertEquals(32, AdaptiveStreaming.capKbps(TranscodeProfile.ORIGINAL, degraded = true))
        assertEquals(256, AdaptiveStreaming.capKbps(TranscodeProfile.ORIGINAL, degraded = false))
        assertEquals(64, AdaptiveStreaming.capKbps(TranscodeProfile.SAVER, degraded = false))
    }

    @Test
    fun `the selector cap lets through exactly the rungs up to the cap`() {
        // continuo's peak BANDWIDTH per rung: kbit/s x 1.06 x 1.25
        val peak = AdaptiveStreaming.LADDER.associateWith { (it * 1000 * 1.06 * 1.25).toInt() }
        for (cap in AdaptiveStreaming.LADDER) {
            val max = AdaptiveStreaming.selectorMaxBitrate(cap)
            val allowed = peak.filterValues { it <= max }.keys.toList()
            assertEquals("cap $cap", AdaptiveStreaming.LADDER.filter { it <= cap }, allowed)
        }
        assertEquals(Int.MAX_VALUE, AdaptiveStreaming.selectorMaxBitrate(256))
    }

    @Test
    fun `the rung playing is read back from the rendition's announced bandwidth`() {
        for (k in AdaptiveStreaming.LADDER) {
            assertEquals(k, AdaptiveStreaming.rungForBandwidth((k * 1000 * 1.06 * 1.25).toInt()))
        }
        assertEquals(null, AdaptiveStreaming.rungForBandwidth(-1))
        assertEquals(null, AdaptiveStreaming.rungForBandwidth(20_000))
    }

    @Test
    fun `songs ahead are encoded at the level playing now, within the cap`() {
        assertEquals(64, AdaptiveStreaming.upcomingKbps(playingKbps = 64, capKbps = 256))
        assertEquals(32, AdaptiveStreaming.upcomingKbps(playingKbps = 128, capKbps = 32))
        assertEquals(128, AdaptiveStreaming.upcomingKbps(playingKbps = null, capKbps = 128))
    }
}
