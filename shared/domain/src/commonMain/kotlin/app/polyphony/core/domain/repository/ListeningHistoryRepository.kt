package app.polyphony.core.domain.repository

import app.polyphony.core.domain.model.PlayContext
import app.polyphony.core.domain.model.RecentContext
import kotlinx.coroutines.flow.Flow

/** Where each recently played collection was left off, most recent first. */
interface ListeningHistoryRepository {
    /** Recent contexts for the active server. */
    fun recent(limit: Int = 20): Flow<List<RecentContext>>
    suspend fun get(context: PlayContext): RecentContext?
    /** Inserts or replaces the entry for `entry.context`. */
    suspend fun record(entry: RecentContext)
    suspend fun remove(context: PlayContext)
    suspend fun clear()
}
