package app.polyphony.core.scrobble.queue

import app.polyphony.core.domain.model.ScrobbleAccount
import app.polyphony.core.domain.model.ScrobbleService
import app.polyphony.core.scrobble.Scrobble
import app.polyphony.core.scrobble.ScrobbleClient
import app.polyphony.core.scrobble.SubmitResult
import app.polyphony.core.scrobble.server.ServerAnswer
import app.polyphony.core.scrobble.server.ServerPlays
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** A play waiting to be sent to one service. */
data class QueuedScrobble(val id: Long, val service: ScrobbleService, val scrobble: Scrobble, val attempts: Int = 0)

/**
 * A counted play waiting for the server: [trackId] on [serverId], played at [playedAtMs]; [scrobble]
 * is the listen for this phone's accounts afterwards (null when they can't take it).
 */
data class PendingServerPlay(
    val id: Long,
    val serverId: String,
    val trackId: String,
    val playedAtMs: Long,
    val scrobble: Scrobble?,
    val attempts: Int = 0,
)

/** Where plays wait (Room in the app, a list in tests); it outlives the process. */
interface ScrobbleQueue {
    suspend fun accounts(): List<ScrobbleAccount>
    suspend fun markNeedsReconnect(service: ScrobbleService)

    /** The oldest [limit] plays the server hasn't been told of, oldest first. */
    suspend fun oldestServerPlays(limit: Int): List<PendingServerPlay>

    /** One more failed attempt at telling the server of [id]. */
    suspend fun serverPlayFailed(id: Long)

    /** The server has [play] (or never will): it's queued for [services] of this phone's, in one step. */
    suspend fun release(play: PendingServerPlay, services: Set<ScrobbleService>)

    /** The oldest [limit] plays for [service], oldest first. */
    suspend fun oldest(service: ScrobbleService, limit: Int): List<QueuedScrobble>
    suspend fun remove(ids: Collection<Long>)

    /** One more failed attempt for each of [ids]. */
    suspend fun recordFailure(ids: Collection<Long>)
}

/**
 * First tells the server of the plays it hasn't heard of yet ([server]), oldest first, and passes
 * each one it took on to this phone's accounts, but not to the services the server scrobbled it to
 * itself. Offline or with the server down, the plays wait (one more attempt counted) and the run
 * asks to be tried again; one the server will never take, or that has failed [MAX_ATTEMPTS] times,
 * still goes to this phone's accounts.
 *
 * Then sends what's queued, service by service, oldest first, in batches of the service's limit:
 * - taken: removed (those the service ignored one by one too; Last.fm's daily limit keeps them),
 * - refused for good: a batch of several is sent again one by one so a single bad listen doesn't
 *   take the others with it; a refused single is dropped,
 * - service down, busy or offline: kept, one more attempt counted, and the run ends asking to be
 *   tried again later (WorkManager's backoff); a short rate-limit wait is waited out in place.
 *   A play that has failed [MAX_ATTEMPTS] times is dropped,
 * - token or session refused: the account is marked to connect again and its plays wait.
 */
class ScrobbleDrainer(
    private val queue: ScrobbleQueue,
    private val clients: List<ScrobbleClient>,
    private val server: ServerPlays? = null,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    enum class Outcome { DONE, RETRY }

    private enum class Step { CONTINUE, STOP_DONE, STOP_RETRY }

    suspend fun drain(): Outcome {
        var retry = server?.let { tellServer(it) } == Outcome.RETRY
        for (account in queue.accounts()) {
            if (account.needsReconnect) continue
            val client = clients.firstOrNull { it.service == account.service } ?: continue
            if (drain(account, client) == Outcome.RETRY) retry = true
        }
        return if (retry) Outcome.RETRY else Outcome.DONE
    }

    private suspend fun tellServer(server: ServerPlays): Outcome {
        while (true) {
            val batch = queue.oldestServerPlays(SERVER_BATCH)
            if (batch.isEmpty()) return Outcome.DONE
            for (play in batch) {
                val covered = when (val answer = server.played(play.serverId, play.trackId, play.playedAtMs)) {
                    is ServerAnswer.Took -> answer.covered
                    ServerAnswer.Never -> emptySet()
                    ServerAnswer.Later -> {
                        if (play.attempts + 1 < MAX_ATTEMPTS) {
                            // Offline or the server down: the rest would fail the same way.
                            queue.serverPlayFailed(play.id)
                            return Outcome.RETRY
                        }
                        emptySet()
                    }
                }
                queue.release(play, if (play.scrobble == null) emptySet() else ownServices(covered))
            }
        }
    }

    /** This phone's accounts that can take a listen, but the services the server already told. */
    private suspend fun ownServices(covered: Set<ScrobbleService>): Set<ScrobbleService> =
        queue.accounts().mapTo(LinkedHashSet()) { it.service }
            .filterTo(LinkedHashSet()) { service -> service !in covered && clients.any { it.service == service } }

    private suspend fun drain(account: ScrobbleAccount, client: ScrobbleClient): Outcome {
        var waits = 0
        while (true) {
            val batch = queue.oldest(account.service, client.batchLimit)
            if (batch.isEmpty()) return Outcome.DONE
            val (sendable, never) = batch.partition { client.accepts(it.scrobble) }
            if (never.isNotEmpty()) queue.remove(never.map { it.id })
            if (sendable.isEmpty()) continue
            val result = submit(client, account, sendable)
            if (result is SubmitResult.Retry && result.afterMs != null && result.afterMs <= MAX_WAIT_MS && waits < MAX_WAITS) {
                waits++
                sleep(result.afterMs)
                continue
            }
            when (apply(account, client, sendable, result)) {
                Step.CONTINUE -> Unit
                Step.STOP_DONE -> return Outcome.DONE
                Step.STOP_RETRY -> return Outcome.RETRY
            }
        }
    }

    private suspend fun apply(account: ScrobbleAccount, client: ScrobbleClient, batch: List<QueuedScrobble>, result: SubmitResult): Step =
        when (result) {
            is SubmitResult.Accepted -> {
                queue.remove(batch.filterIndexed { i, _ -> i !in result.deferred }.map { it.id })
                if (result.deferred.isEmpty()) Step.CONTINUE else Step.STOP_RETRY
            }
            is SubmitResult.Retry -> {
                queue.recordFailure(batch.map { it.id })
                val exhausted = batch.filter { it.attempts + 1 >= MAX_ATTEMPTS }.map { it.id }
                if (exhausted.isNotEmpty()) queue.remove(exhausted)
                Step.STOP_RETRY
            }
            is SubmitResult.Rejected -> if (batch.size == 1) {
                queue.remove(batch.map { it.id })
                Step.CONTINUE
            } else {
                oneByOne(account, client, batch)
            }
            SubmitResult.Unauthorized -> {
                queue.markNeedsReconnect(account.service)
                Step.STOP_DONE
            }
        }

    private suspend fun oneByOne(account: ScrobbleAccount, client: ScrobbleClient, batch: List<QueuedScrobble>): Step {
        for (item in batch) {
            val step = apply(account, client, listOf(item), submit(client, account, listOf(item)))
            if (step != Step.CONTINUE) return step
        }
        return Step.CONTINUE
    }

    private suspend fun submit(client: ScrobbleClient, account: ScrobbleAccount, batch: List<QueuedScrobble>): SubmitResult =
        try {
            client.submit(account, batch.map { it.scrobble })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SubmitResult.Retry(e.message ?: e.javaClass.simpleName)
        }

    companion object {
        /** A play that has failed this often (over days, with the backoff) is given up on. */
        const val MAX_ATTEMPTS = 30

        /** Rate-limit waits up to this long are waited out within the run, a few times at most. */
        const val MAX_WAIT_MS = 60_000L
        const val MAX_WAITS = 3

        /** Plays read from the server's queue at a time. */
        const val SERVER_BATCH = 50
    }
}
