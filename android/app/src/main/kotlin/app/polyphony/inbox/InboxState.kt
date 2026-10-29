package app.polyphony.inbox

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unread counts per continuo server, as last reported by continuo: the poll, the inbox and every
 * mark-read update it, and the Requests screen's inbox badge shows it.
 */
@Singleton
class InboxBadges @Inject constructor() {
    private val counts = MutableStateFlow<Map<String, Int>>(emptyMap())

    fun unread(serverId: String): Flow<Int> = counts.map { it[serverId] ?: 0 }.distinctUntilChanged()

    fun set(serverId: String, unread: Int) = counts.update { it + (serverId to unread.coerceAtLeast(0)) }

    fun decrement(serverId: String, by: Int = 1) =
        counts.update { it + (serverId to ((it[serverId] ?: 0) - by).coerceAtLeast(0)) }
}

/**
 * The newest notification id seen per server, so the background poll posts each notification
 * once. Kept out of the server's options on purpose: those also hold the rotating refresh token,
 * and two writers racing there could drop a rotated token.
 */
@Singleton
class InboxCursors @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("continuo_inbox", Context.MODE_PRIVATE)

    /** null when the server has never been polled. */
    @Synchronized
    fun lastSeen(serverId: String): Long? = prefs.getLong(key(serverId), -1L).takeIf { it >= 0 }

    /** Moves the cursor forward to [id]; never back. */
    @Synchronized
    fun advance(serverId: String, id: Long) {
        val current = lastSeen(serverId)
        if (current == null || id > current) prefs.edit().putLong(key(serverId), id.coerceAtLeast(0)).apply()
    }

    private fun key(serverId: String) = "lastSeen.$serverId"
}
