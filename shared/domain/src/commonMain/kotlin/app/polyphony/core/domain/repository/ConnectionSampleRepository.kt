package app.polyphony.core.domain.repository

import app.polyphony.core.domain.connection.ConnectionSample

/** The connection samples measured while streaming (#185), kept on the phone for a while. */
interface ConnectionSampleRepository {
    /** Stores [sample] and prunes what is past `ConnectionRetention`. */
    suspend fun record(sample: ConnectionSample)

    /** Every sample kept, oldest first. */
    suspend fun all(): List<ConnectionSample>

    /** [serverId]'s samples that ended after [afterMs], oldest first, at most [limit]: what's left to send it. */
    suspend fun forServerAfter(serverId: String, afterMs: Long, limit: Int): List<ConnectionSample> =
        all().filter { it.serverId == serverId && it.endedAtMs > afterMs }.take(limit)

    suspend fun clear()
}
