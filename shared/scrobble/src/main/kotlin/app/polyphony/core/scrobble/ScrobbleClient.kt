package app.polyphony.core.scrobble

import app.polyphony.core.domain.model.ScrobbleAccount
import app.polyphony.core.domain.model.ScrobbleService

/** One scrobbling service, as the queue sees it. */
interface ScrobbleClient {
    val service: ScrobbleService

    /** The most listens one submission may carry (the service's own limit, or less). */
    val batchLimit: Int

    /** Whether the service takes this listen at all; one it never would is dropped unsent. */
    fun accepts(scrobble: Scrobble): Boolean = true

    /** Best effort: a failure is the caller's to ignore. */
    suspend fun nowPlaying(account: ScrobbleAccount, scrobble: Scrobble)

    /** Never throws for an answer from the service; may throw IOException when it can't be reached. */
    suspend fun submit(account: ScrobbleAccount, batch: List<Scrobble>): SubmitResult
}

/** What a service made of a submission, which decides what the queue does with it. */
sealed interface SubmitResult {
    /**
     * The service took the submission. [ignored]: positions in the batch it refused one by one (too
     * old, a filtered artist): dropped. [deferred]: positions to send again later (Last.fm's daily
     * limit): kept.
     */
    data class Accepted(val ignored: Set<Int> = emptySet(), val deferred: Set<Int> = emptySet()) : SubmitResult

    /** Worth trying again later: the service is down, busy or rate limiting ([afterMs] when it says). */
    data class Retry(val reason: String, val afterMs: Long? = null) : SubmitResult

    /** Refused for good (an invalid listen): never sent again. */
    data class Rejected(val reason: String) : SubmitResult

    /** The token or session was refused: plays wait until the account is connected again. */
    data object Unauthorized : SubmitResult
}
