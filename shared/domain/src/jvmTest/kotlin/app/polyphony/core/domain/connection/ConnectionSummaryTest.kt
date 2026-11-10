package app.polyphony.core.domain.connection

import app.polyphony.core.domain.connection.ConnectionSample.Trigger
import app.polyphony.core.domain.transcode.NetworkKind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionSummaryTest {
    private fun sample(
        network: NetworkKind,
        endedAtMs: Long = 30_000,
        durationMs: Long = 30_000,
        throughputKbps: Int? = null,
        stalls: Int = 0,
        cappedMs: Long = 0,
    ) = ConnectionSample(
        startedAtMs = endedAtMs - durationMs,
        endedAtMs = endedAtMs,
        trigger = Trigger.PERIODIC,
        network = network,
        metered = network == NetworkKind.CELLULAR,
        throughputKbps = throughputKbps,
        stalls = stalls,
        cappedMs = cappedMs,
    )

    @Test
    fun `one row per network kind with median throughput, stalls per hour and time capped`() {
        val samples = listOf(
            sample(NetworkKind.CELLULAR, throughputKbps = 300, stalls = 1, durationMs = 1_800_000),
            sample(NetworkKind.WIFI, throughputKbps = 20_000),
            sample(NetworkKind.CELLULAR, throughputKbps = 100, stalls = 2, durationMs = 1_800_000, cappedMs = 600_000),
            sample(NetworkKind.CELLULAR, throughputKbps = null, durationMs = 0),
            sample(NetworkKind.CELLULAR, throughputKbps = 900, durationMs = 0),
        )
        val rows = ConnectionSummary.of(samples)
        assertEquals(listOf(NetworkKind.WIFI, NetworkKind.CELLULAR), rows.map { it.network })
        val cell = rows[1]
        assertEquals(4, cell.samples)
        // 100, 300, 900: the unmeasured sample doesn't count.
        assertEquals(300, cell.medianThroughputKbps)
        assertEquals(3, cell.stalls)
        assertEquals(3.0, cell.stallsPerHour, 1e-9)
        assertEquals(600_000L, cell.cappedMs)
        assertEquals(3_600_000L, cell.streamedMs)
    }

    @Test
    fun `a kind never measured has no median and no stall rate`() {
        val row = ConnectionSummary.of(listOf(sample(NetworkKind.ETHERNET, durationMs = 0))).single()
        assertNull(row.medianThroughputKbps)
        assertEquals(0.0, row.stallsPerHour, 0.0)
        assertTrue(ConnectionSummary.of(emptyList()).isEmpty())
    }

    @Test
    fun `rows read as one line each`() {
        val rows = ConnectionSummary.of(
            listOf(sample(NetworkKind.CELLULAR, throughputKbps = 2_450, stalls = 3, durationMs = 3_600_000, cappedMs = 300_000)),
        )
        assertEquals(
            listOf("Mobile data: 1 sample (1 h 0 min), median 2.5 Mbit/s, 3.0 stalls/h, capped 5 min"),
            ConnectionSummary.describe(rows),
        )
    }

    @Test
    fun `retention drops samples past the age and keeps only the newest rows`() {
        val day = 24 * 3_600_000L
        val now = 30 * day
        val retention = ConnectionRetention(maxAgeMs = 14 * day, maxRows = 2)
        val old = sample(NetworkKind.WIFI, endedAtMs = now - 15 * day)
        val a = sample(NetworkKind.WIFI, endedAtMs = now - 3 * day)
        val b = sample(NetworkKind.WIFI, endedAtMs = now - 2 * day)
        val c = sample(NetworkKind.WIFI, endedAtMs = now - day)
        assertEquals(now - 14 * day, retention.cutoffMs(now))
        assertEquals(listOf(c, b), retention.retain(listOf(a, old, c, b), now))
        assertEquals(listOf(a), ConnectionRetention(maxAgeMs = 14 * day).retain(listOf(old, a), now))
    }

    @Test
    fun `a batch serialises with its samples as they are stored`() {
        val json = Json { encodeDefaults = true }
        val batch = ConnectionSampleBatch(listOf(sample(NetworkKind.CELLULAR, throughputKbps = 640, stalls = 1)))
        val text = json.encodeToString(ConnectionSampleBatch.serializer(), batch)
        assertTrue(text, text.contains("\"network\":\"CELLULAR\""))
        assertTrue(text, text.contains("\"throughputKbps\":640"))
        assertEquals(batch, json.decodeFromString(ConnectionSampleBatch.serializer(), text))
    }
}
