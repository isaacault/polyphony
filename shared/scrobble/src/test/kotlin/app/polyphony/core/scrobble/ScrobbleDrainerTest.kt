package app.polyphony.core.scrobble

import app.polyphony.core.domain.model.ScrobbleAccount
import app.polyphony.core.domain.model.ScrobbleService
import app.polyphony.core.scrobble.lastfm.LastFmClient
import app.polyphony.core.scrobble.lastfm.LastFmConfig
import app.polyphony.core.scrobble.listenbrainz.ListenBrainzClient
import app.polyphony.core.scrobble.queue.QueuedScrobble
import app.polyphony.core.scrobble.queue.ScrobbleDrainer
import app.polyphony.core.scrobble.queue.ScrobbleQueue
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
    }

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
}
