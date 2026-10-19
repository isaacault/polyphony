package app.polyphony.core.domain.transcode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscodeResolverTest {

    private val resolver = TranscodeResolver()
    private val profiles = TranscodeProfile.BUILT_INS
    private val wifi = NetworkState(NetworkKind.WIFI, isMetered = false)
    private val cellular = NetworkState(NetworkKind.CELLULAR, isMetered = true)
    private val tv = OutputTarget.Cast("abc", "Living room TV")

    @Test
    fun `default policy on wifi streams original`() {
        val d = resolver.resolve(TranscodePolicy(), profiles, TranscodeContext(wifi))
        assertEquals(TranscodeProfile.ORIGINAL_ID, d.profile.id)
        assertEquals(TranscodeDecision.Reason.DEFAULT, d.reason)
    }

    @Test
    fun `cellular rule applies on mobile data`() {
        val d = resolver.resolve(TranscodePolicy(), profiles, TranscodeContext(cellular))
        assertEquals(TranscodeProfile.BALANCED_ID, d.profile.id)
        assertEquals(TranscodeDecision.Reason.CELLULAR_RULE, d.reason)
    }

    @Test
    fun `metered wifi is treated as cellular when enabled`() {
        val metered = NetworkState(NetworkKind.WIFI, isMetered = true)
        val d = resolver.resolve(TranscodePolicy(meteredWifiAsCellular = true), profiles, TranscodeContext(metered))
        assertEquals(TranscodeDecision.Reason.CELLULAR_RULE, d.reason)
        val d2 = resolver.resolve(TranscodePolicy(meteredWifiAsCellular = false, wifiProfileId = TranscodeProfile.HIGH_ID), profiles, TranscodeContext(metered))
        assertEquals(TranscodeDecision.Reason.WIFI_RULE, d2.reason)
    }

    @Test
    fun `per device override beats everything`() {
        val policy = TranscodePolicy(castDefaultProfileId = TranscodeProfile.CAST_SAFE_ID, cellularProfileId = TranscodeProfile.SAVER_ID)
        val d = resolver.resolve(policy, profiles, TranscodeContext(cellular, tv, deviceProfileId = TranscodeProfile.LOSSLESS_CAPPED_ID))
        assertEquals(TranscodeProfile.LOSSLESS_CAPPED_ID, d.profile.id)
        assertEquals(TranscodeDecision.Reason.DEVICE_OVERRIDE, d.reason)
    }

    @Test
    fun `cast default used when no device override`() {
        val policy = TranscodePolicy(castDefaultProfileId = TranscodeProfile.CAST_SAFE_ID)
        val d = resolver.resolve(policy, profiles, TranscodeContext(wifi, tv))
        assertEquals(TranscodeProfile.CAST_SAFE_ID, d.profile.id)
        assertEquals(TranscodeDecision.Reason.CAST_DEFAULT, d.reason)
    }

    @Test
    fun `remote output without cast rules falls back to network rules`() {
        val d = resolver.resolve(TranscodePolicy(), profiles, TranscodeContext(cellular, tv))
        assertEquals(TranscodeDecision.Reason.CELLULAR_RULE, d.reason)
    }

    @Test
    fun `latency degradation caps quality`() {
        val d = resolver.resolve(TranscodePolicy(), profiles, TranscodeContext(wifi, latencyDegraded = true))
        assertEquals(TranscodeProfile.BALANCED_ID, d.profile.id)
        assertTrue(d.cappedByLatency)
        assertEquals(TranscodeDecision.Reason.DEFAULT, d.reason)
    }

    @Test
    fun `latency cap never raises quality`() {
        val policy = TranscodePolicy(cellularProfileId = TranscodeProfile.SAVER_ID)
        val d = resolver.resolve(policy, profiles, TranscodeContext(cellular, latencyDegraded = true))
        assertEquals(TranscodeProfile.SAVER_ID, d.profile.id)
        assertFalse(d.cappedByLatency)
    }

    @Test
    fun `latency cap respects remote opt-out`() {
        val policy = TranscodePolicy(adaptiveLatency = AdaptiveLatencySettings(applyToRemoteOutputs = false))
        val d = resolver.resolve(policy, profiles, TranscodeContext(wifi, tv, latencyDegraded = true))
        assertFalse(d.cappedByLatency)
        assertEquals(TranscodeProfile.ORIGINAL_ID, d.profile.id)
    }

    @Test
    fun `unknown profile ids fall back to original`() {
        val policy = TranscodePolicy(defaultProfileId = "missing", cellularProfileId = "missing-too")
        val d = resolver.resolve(policy, profiles, TranscodeContext(cellular))
        assertEquals(TranscodeProfile.ORIGINAL_ID, d.profile.id)
    }

    @Test
    fun `custom profiles are honoured`() {
        val custom = TranscodeProfile("custom.1", "Bedroom speaker", AudioCodec.AAC, maxBitrateKbps = 192)
        val d = resolver.resolve(TranscodePolicy(), profiles + custom, TranscodeContext(wifi, tv, deviceProfileId = "custom.1"))
        assertEquals("custom.1", d.profile.id)
    }
}
