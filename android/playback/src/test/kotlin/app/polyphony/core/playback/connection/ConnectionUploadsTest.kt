package app.polyphony.core.playback.connection

import app.polyphony.core.domain.connection.ConnectionSample
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerType
import app.polyphony.core.domain.repository.ConnectionSampleRepository
import app.polyphony.core.domain.transcode.NetworkKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class ConnectionUploadsTest {

    private fun server(id: String) = Server(id, ServerType.SUBSONIC, id, "https://$id", "u", "p", deviceId = "d")

    private fun sample(serverId: String, endedAtMs: Long) =
        ConnectionSample(endedAtMs - 30_000, endedAtMs, ConnectionSample.Trigger.PERIODIC, NetworkKind.CELLULAR, metered = true, serverId = serverId)

    private class Samples(val rows: List<ConnectionSample>) : ConnectionSampleRepository {
        override suspend fun record(sample: ConnectionSample) = Unit
        override suspend fun all() = rows.sortedBy { it.endedAtMs }
        override suspend fun clear() = Unit
    }

    private class Marks : ConnectionUploads.SentMarks {
        val through = HashMap<String, Long>()
        override fun sentThrough(serverId: String) = through[serverId] ?: 0L
        override fun setSentThrough(serverId: String, endedAtMs: Long) { through[serverId] = endedAtMs }
    }

    private var now = 1_000_000L

    @Test fun `each continuo gets its own samples, oldest first, and only once`() = runTest {
        val rows = (1..250).map { sample("home", it * 1_000L) } + sample("other", 5_000L) + sample("plain", 6_000L)
        val sent = mutableListOf<Pair<String, List<Long>>>()
        val marks = Marks()
        val uploads = ConnectionUploads(
            servers = { listOf(server("home"), server("other"), server("plain")) },
            supports = { it.id != "plain" },   // not a continuo
            send = { s, batch -> sent += s.id to batch.map { it.endedAtMs }; true },
            samples = Samples(rows), marks = marks, nowMs = { now },
        )
        uploads.run()
        assertEquals(listOf("home", "home", "other"), sent.map { it.first })
        assertEquals(ConnectionUploads.BATCH, sent[0].second.size)
        assertEquals(1_000L, sent[0].second.first())
        assertEquals(250_000L, marks.through["home"])

        sent.clear()
        uploads.run()
        assertEquals("nothing sent twice", emptyList<Pair<String, List<Long>>>(), sent)
    }

    @Test fun `offline they wait, and a continuo without the endpoint is left alone for hours`() = runTest {
        val marks = Marks()
        var reachable = false
        var takes = false
        var asked = 0
        val uploads = ConnectionUploads(
            servers = { listOf(server("home")) },
            supports = { true },
            send = { _, _ -> asked++; if (!reachable) throw IOException("offline"); takes },
            samples = Samples(listOf(sample("home", 1_000L))), marks = marks, nowMs = { now },
        )
        uploads.run()
        assertEquals(0L, marks.sentThrough("home"))

        reachable = true
        uploads.run()   // reached, but it doesn't take them
        assertEquals(2, asked)
        now += ConnectionUploads.BACKOFF_MS - 1
        uploads.run()
        assertEquals("not asked again within the backoff", 2, asked)

        takes = true
        now += 2
        uploads.run()
        assertEquals(1_000L, marks.sentThrough("home"))
    }
}
