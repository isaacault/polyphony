package app.polyphony.diagnostics

import app.polyphony.core.domain.connection.ConnectionSample
import app.polyphony.core.domain.transcode.AudioCodec
import app.polyphony.core.domain.transcode.NetworkKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionSamplesCsvTest {

    @Test
    fun `one header and one row per sample, unknowns left empty`() {
        val sample = ConnectionSample(
            startedAtMs = 0, endedAtMs = 30_000, trigger = ConnectionSample.Trigger.PERIODIC,
            network = NetworkKind.CELLULAR, metered = true, networkBytes = 120_000, networkMs = 1_500,
            throughputKbps = 640, stalls = 1, stallMs = 2_000, profileId = "user,1", codec = AudioCodec.OPUS,
        )
        val text = StringBuilder().also { ConnectionSamplesCsv.write(listOf(sample), it) }.toString()
        val lines = text.trimEnd().lines()
        assertEquals(2, lines.size)
        val header = lines[0].split(",")
        assertEquals("startedAtMs", header.first())
        assertEquals("cappedMs", header.last())
        assertEquals(
            "0,30000,30000,PERIODIC,CELLULAR,true,false,,,120000,1500,640,0,0,NETWORK,1,2000,,\"user,1\",OPUS,,,false,0",
            lines[1],
        )
    }
}
