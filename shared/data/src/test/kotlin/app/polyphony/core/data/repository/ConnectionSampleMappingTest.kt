package app.polyphony.core.data.repository

import app.polyphony.core.domain.connection.ConnectionSample
import app.polyphony.core.domain.transcode.AudioCodec
import app.polyphony.core.domain.transcode.NetworkKind
import app.polyphony.core.domain.transcode.TranscodeDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionSampleMappingTest {

    @Test
    fun `a sample survives the round trip through its row`() {
        val sample = ConnectionSample(
            startedAtMs = 1_000, endedAtMs = 31_000, trigger = ConnectionSample.Trigger.NETWORK_CHANGE,
            network = NetworkKind.CELLULAR, metered = true, vpn = true, atHome = false, responseMs = 240,
            networkBytes = 600_000, networkMs = 4_000, throughputKbps = 1_200, cachedBytes = 10, downloadedBytes = 0,
            stalls = 2, stallMs = 3_100, serverId = "s1", profileId = "builtin.high", codec = AudioCodec.OPUS,
            maxBitrateKbps = 160, reason = TranscodeDecision.Reason.CELLULAR_RULE, capped = true, cappedMs = 9_000,
        )
        assertEquals(sample, sample.toEntity().toDomain())
    }

    @Test
    fun `values from a newer build fall back instead of failing`() {
        val row = ConnectionSample(0, 30_000, ConnectionSample.Trigger.PERIODIC, NetworkKind.WIFI, metered = false)
            .toEntity().copy(endedBy = "SOMETHING_NEW", network = "SATELLITE", codec = "LC3", reason = "NEW")
        val sample = row.toDomain()
        assertEquals(ConnectionSample.Trigger.PERIODIC, sample.trigger)
        assertEquals(NetworkKind.OTHER, sample.network)
        assertNull(sample.codec)
        assertNull(sample.reason)
    }
}
