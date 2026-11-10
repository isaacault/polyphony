package app.polyphony.core.domain.repository

import app.polyphony.core.domain.connection.ConnectionSample

/** The connection samples measured while streaming (#185), kept on the phone for a while. */
interface ConnectionSampleRepository {
    /** Stores [sample] and prunes what is past `ConnectionRetention`. */
    suspend fun record(sample: ConnectionSample)

    /** Every sample kept, oldest first. */
    suspend fun all(): List<ConnectionSample>

    suspend fun clear()
}
