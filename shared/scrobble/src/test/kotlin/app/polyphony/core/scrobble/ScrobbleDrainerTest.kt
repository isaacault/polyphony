package app.polyphony.core.scrobble

import app.polyphony.core.domain.model.ScrobbleAccount
import app.polyphony.core.domain.model.ScrobbleService
import app.polyphony.core.scrobble.lastfm.LastFmClient
import app.polyphony.core.scrobble.lastfm.LastFmConfig
import app.polyphony.core.scrobble.listenbrainz.ListenBrainzClient
import app.polyphony.core.scrobble.queue.PendingServerPlay
import app.polyphony.core.scrobble.queue.QueuedScrobble
import app.polyphony.core.scrobble.queue.ScrobbleDrainer
import app.polyphony.core.scrobble.queue.ScrobbleQueue
import app.polyphony.core.scrobble.server.ServerAnswer
import app.polyphony.core.scrobble.server.ServerPlays
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrobbleDrainerTest {

    private class FakeQueue(vararg accounts: ScrobbleAccount) : ScrobbleQueue {
        val accounts = accounts.toMutableList()
        val items = mutableListOf<QueuedScrobble>()
        private var nextId = 1L

        fun add(service: ScrobbleService, count: Int, durationMs: Long = 200_000) = repeat(count) {
            val id = nextId++
            items += QueuedScrobble(id, service, Scrobble("Artist $id", "Track $id", listenedAtMs = id * 1_000_000, durationMs = durationMs))
        }

        fun ids(service: ScrobbleService) = items.filter { it.service == service }.map { it.id }

        override suspend fun accounts() = accounts.toList()
        override suspend fun markNeedsReconnect(service: ScrobbleService) {
            accounts.replaceAll { if (it.service == service) it.copy(needsReconnect = true) else it }
        }
        override suspend fun oldest(service: ScrobbleService, limit: Int) = items.filter { it.service == service }.sortedBy { it.id }.take(limit)
        override suspend fun remove(ids: Collection<Long>) {
            items.removeAll { it.id in ids }
        }
        override suspend fun recordFailure(ids: Collection<Long>) {
            items.replaceAll { if (it.id in ids) it.copy(attempts = it.attempts + 1) else it }
        }

        val serverPlays = mutableListOf<PendingServerPlay>()

        fun played(trackId: String, attempts: Int = 0, listenable: Boolean = true) {
            val id = nextId++
            val scrobble = Scrobble("Artist $id", "Track $id", listenedAtMs = id * 1_000_000, durationMs = 200_000).takeIf { listenable }
            serverPlays += PendingServerPlay(id, "navidrome", trackId, id * 1_000_000, scrobble, attempts)
        }

        override suspend fun oldestServerPlays(limit: Int) = serverPlays.sortedBy { it.id }.take(limit)
        override suspend fun serverPlayFailed(id: Long) {
            serverPlays.replaceAll { if (it.id == id) it.copy(attempts = it.attempts + 1) else it }
        }
        override suspend fun release(play: PendingServerPlay, services: Set<ScrobbleService>) {
            serverPlays.removeAll { it.id == play.id }
            play.scrobble?.let { scrobble -> services.forEach { items += QueuedScrobble(nextId++, it, scrobble) } }
        }
    }

    /** The server's answer per track id, and the plays it was told of, in order. */
    private class FakeServer(val answer: (String) -> ServerAnswer) : ServerPlays {
        val told = mutableListOf<String>()
        override suspend fun played(serverId: String, trackId: String, playedAtMs: Long): ServerAnswer {
            told += trackId
            return answer(trackId)
        }
        override suspend fun nowPlaying(serverId: String, trackId: String) = emptySet<ScrobbleService>()
    }

    private fun accepting(service: ScrobbleService) = ScriptedClient(service, 50) { SubmitResult.Accepted() }

    /** Answers each submission with [answer] and keeps the batches it was given. */
    private class ScriptedClient(
        override val service: ScrobbleService,
        override val batchLimit: Int,
        val answer: (List<Scrobble>) -> SubmitResult,
    ) : ScrobbleClient {
        val batches = mutableListOf<List<Scrobble>>()
        override suspend fun nowPlaying(account: ScrobbleAccount, scrobble: Scrobble) = Unit
        override suspend fun submit(account: ScrobbleAccount, batch: List<Scrobble>): SubmitResult {
            batches += batch
            return answer(batch)
        }
    }

    private val lb = ScrobbleAccount(ScrobbleService.LISTENBRAINZ, "alice", "tok")
    private val fm = ScrobbleAccount(ScrobbleService.LASTFM, "alice", "sk")

    @Test
    fun `everything goes out in batches of each service's limit, oldest first`() = runTest {
        val queue = FakeQueue(lb, fm).apply {
            add(ScrobbleService.LASTFM, 120)
            add(ScrobbleService.LISTENBRAINZ, 150)
        }
        val http = FakeHttp()
        repeat(2) { http.answer(200, """{"status":"ok"}""") }
        repeat(3) { http.answer(200, """{"scrobbles":{"scrobble":[],"@attr":{"accepted":50,"ignored":0}}}""") }
        val drainer = ScrobbleDrainer(queue, listOf(ListenBrainzClient(http, "1"), LastFmClient(http, LastFmConfig("k", "s"))))
        assertEquals(ScrobbleDrainer.Outcome.DONE, drainer.drain())
        assertTrue(queue.items.isEmpty())
        // The accounts' order: ListenBrainz (100 + 50), then Last.fm (50 + 50 + 20).
        val sizes = http.requests.map { r ->
            if (r.url.contains("listenbrainz")) r.body!!.split("\"listened_at\"").size - 1
            else formFields(r.body).count { it.first.startsWith("timestamp[") }
        }
        assertEquals(listOf(100, 50, 50, 50, 20), sizes)
        val firstLastFm = formFields(http.requests[2].body).toMap()
        assertEquals("Artist 1", firstLastFm["artist[0]"])
    }

    @Test
    fun `offline, every play stays and the run asks to be tried again`() = runTest {
        val queue = FakeQueue(lb).apply { add(ScrobbleService.LISTENBRAINZ, 3) }
        val http = FakeHttp().apply { offline = true }
        assertEquals(ScrobbleDrainer.Outcome.RETRY, ScrobbleDrainer(queue, listOf(ListenBrainzClient(http, "1"))).drain())
        assertEquals(listOf(1L, 2L, 3L), queue.ids(ScrobbleService.LISTENBRAINZ))
        assertTrue(queue.items.all { it.attempts == 1 })
    }

    @Test
    fun `a play that keeps failing is given up on after the last attempt`() = runTest {
        val queue = FakeQueue(lb).apply { add(ScrobbleService.LISTENBRAINZ, 2) }
        queue.items[0] = queue.items[0].copy(attempts = ScrobbleDrainer.MAX_ATTEMPTS - 1)
        val client = ScriptedClient(ScrobbleService.LISTENBRAINZ, 10) { SubmitResult.Retry("503") }
        assertEquals(ScrobbleDrainer.Outcome.RETRY, ScrobbleDrainer(queue, listOf(client)).drain())
        assertEquals(listOf(2L), queue.ids(ScrobbleService.LISTENBRAINZ))
    }

    @Test
    fun `a refused batch is sent one by one, and only the bad listen is dropped`() = runTest {
        val queue = FakeQueue(lb).apply { add(ScrobbleService.LISTENBRAINZ, 3) }
        val client = ScriptedClient(ScrobbleService.LISTENBRAINZ, 10) { batch ->
            if (batch.any { it.track == "Track 2" }) SubmitResult.Rejected("invalid") else SubmitResult.Accepted()
        }
        assertEquals(ScrobbleDrainer.Outcome.DONE, ScrobbleDrainer(queue, listOf(client)).drain())
        assertTrue(queue.items.isEmpty())
        assertEquals(listOf(3, 1, 1, 1), client.batches.map { it.size })
    }

    @Test
    fun `listens the service ignored are dropped, the daily limit's are kept for later`() = runTest {
        val queue = FakeQueue(fm).apply { add(ScrobbleService.LASTFM, 3) }
        val client = ScriptedClient(ScrobbleService.LASTFM, 50) { SubmitResult.Accepted(ignored = setOf(0), deferred = setOf(2)) }
        assertEquals(ScrobbleDrainer.Outcome.RETRY, ScrobbleDrainer(queue, listOf(client)).drain())
        assertEquals(listOf(3L), queue.ids(ScrobbleService.LASTFM))
        assertEquals("a deferral isn't a failure", 0, queue.items.single().attempts)
    }

    @Test
    fun `a refused token marks the account and keeps its plays without retrying`() = runTest {
        val queue = FakeQueue(lb, fm).apply {
            add(ScrobbleService.LISTENBRAINZ, 2)
            add(ScrobbleService.LASTFM, 1)
        }
        val lbClient = ScriptedClient(ScrobbleService.LISTENBRAINZ, 10) { SubmitResult.Unauthorized }
        val fmClient = ScriptedClient(ScrobbleService.LASTFM, 50) { SubmitResult.Accepted() }
        assertEquals(ScrobbleDrainer.Outcome.DONE, ScrobbleDrainer(queue, listOf(lbClient, fmClient)).drain())
        assertEquals(listOf(1L, 2L), queue.ids(ScrobbleService.LISTENBRAINZ))
        assertTrue(queue.accounts.first { it.service == ScrobbleService.LISTENBRAINZ }.needsReconnect)
        assertEquals("the other service still went", emptyList<Long>(), queue.ids(ScrobbleService.LASTFM))

        // Until it's connected again, nothing is sent for it.
        ScrobbleDrainer(queue, listOf(lbClient, fmClient)).drain()
        assertEquals(1, lbClient.batches.size)
    }

    @Test
    fun `a short rate limit is waited out in place, a long one left to the backoff`() = runTest {
        val queue = FakeQueue(lb).apply { add(ScrobbleService.LISTENBRAINZ, 1) }
        var calls = 0
        val client = ScriptedClient(ScrobbleService.LISTENBRAINZ, 10) {
            if (calls++ == 0) SubmitResult.Retry("rate limit", afterMs = 5_000) else SubmitResult.Accepted()
        }
        val slept = mutableListOf<Long>()
        assertEquals(ScrobbleDrainer.Outcome.DONE, ScrobbleDrainer(queue, listOf(client)) { slept += it }.drain())
        assertEquals(listOf(5_000L), slept)
        assertTrue(queue.items.isEmpty())

        queue.add(ScrobbleService.LISTENBRAINZ, 1)
        val slow = ScriptedClient(ScrobbleService.LISTENBRAINZ, 10) { SubmitResult.Retry("rate limit", afterMs = 600_000) }
        assertEquals(ScrobbleDrainer.Outcome.RETRY, ScrobbleDrainer(queue, listOf(slow)) { slept += it }.drain())
        assertEquals(listOf(5_000L), slept)
        assertEquals(1, queue.items.single().attempts)
    }

    @Test
    fun `a listen Last fm would never take is dropped unsent`() = runTest {
        val queue = FakeQueue(fm).apply { add(ScrobbleService.LASTFM, 1, durationMs = 20_000) }
        val http = FakeHttp()
        assertEquals(ScrobbleDrainer.Outcome.DONE, ScrobbleDrainer(queue, listOf(LastFmClient(http, LastFmConfig("k", "s")))).drain())
        assertTrue(queue.items.isEmpty())
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `a service the server scrobbled to doesn't hear the play from the phone too`() = runTest {
        val queue = FakeQueue(lb, fm).apply { played("song"); played("pn-stream") }
        val server = FakeServer { id ->
            // Navidrome scrobbles library songs to ListenBrainz; continuo reached both for Play now.
            if (id == "song") ServerAnswer.Took(setOf(ScrobbleService.LISTENBRAINZ))
            else ServerAnswer.Took(setOf(ScrobbleService.LISTENBRAINZ, ScrobbleService.LASTFM))
        }
        val lbClient = accepting(ScrobbleService.LISTENBRAINZ)
        val fmClient = accepting(ScrobbleService.LASTFM)
        assertEquals(ScrobbleDrainer.Outcome.DONE, ScrobbleDrainer(queue, listOf(lbClient, fmClient), server = server).drain())
        assertEquals(listOf("song", "pn-stream"), server.told)
        assertTrue(queue.serverPlays.isEmpty())
        assertTrue("ListenBrainz had both from the server", lbClient.batches.isEmpty())
        assertEquals(listOf("Track 1"), fmClient.batches.flatten().map { it.track })
    }

    @Test
    fun `offline, plays wait for the server with their time, and the phone's accounts wait with them`() = runTest {
        val queue = FakeQueue(lb).apply { played("a"); played("b") }
        val server = FakeServer { ServerAnswer.Later }
        val lbClient = accepting(ScrobbleService.LISTENBRAINZ)
        assertEquals(ScrobbleDrainer.Outcome.RETRY, ScrobbleDrainer(queue, listOf(lbClient), server = server).drain())
        assertEquals("stops at the first: the rest would fail the same way", listOf("a"), server.told)
        assertEquals(listOf(1, 0), queue.serverPlays.map { it.attempts })
        assertTrue(lbClient.batches.isEmpty())

        // Back online: both reach the server, oldest first, then the phone's account.
        val online = FakeServer { ServerAnswer.Took(emptySet()) }
        assertEquals(ScrobbleDrainer.Outcome.DONE, ScrobbleDrainer(queue, listOf(lbClient), server = online).drain())
        assertEquals(listOf("a", "b"), online.told)
        assertEquals(listOf(1_000_000L, 2_000_000L), lbClient.batches.flatten().map { it.listenedAtMs })
    }

    @Test
    fun `a play the server won't take, or keeps failing, still goes to the phone's accounts`() = runTest {
        val queue = FakeQueue(lb).apply {
            played("gone")
            played("stuck", attempts = ScrobbleDrainer.MAX_ATTEMPTS - 1)
            played("anonymous", listenable = false)
        }
        val server = FakeServer { id -> if (id == "gone" || id == "anonymous") ServerAnswer.Never else ServerAnswer.Later }
        val lbClient = accepting(ScrobbleService.LISTENBRAINZ)
        assertEquals(ScrobbleDrainer.Outcome.DONE, ScrobbleDrainer(queue, listOf(lbClient), server = server).drain())
        assertTrue(queue.serverPlays.isEmpty())
        assertEquals("a song without an artist can't be a listen", listOf("Track 1", "Track 2"), lbClient.batches.flatten().map { it.track })
    }

    @Test
    fun `a service this build can't send to isn't queued`() = runTest {
        val queue = FakeQueue(lb, fm).apply { played("song") }
        val lbClient = accepting(ScrobbleService.LISTENBRAINZ)
        // No Last.fm client: a build without the Last.fm key.
        ScrobbleDrainer(queue, listOf(lbClient), server = FakeServer { ServerAnswer.Took(emptySet()) }).drain()
        assertEquals(1, lbClient.batches.flatten().size)
        assertTrue("nothing left waiting for Last.fm", queue.items.isEmpty())
    }
}
