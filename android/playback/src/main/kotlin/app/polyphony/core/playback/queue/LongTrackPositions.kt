package app.polyphony.core.playback.queue

/**
 * Where long songs (mixes, live sets, audiobooks: [LONG_MS] and more) were left, so choosing one
 * again picks up there (#37). On the phone, the newest [MAX] of them, with the server's bookmarks
 * filling in songs this phone has no place for. Not thread-safe: the playback service uses it on
 * the main thread and writes [serialize] to a file.
 */
class LongTrackPositions {
    private val positions = LinkedHashMap<String, Long>()   // track id -> ms, least recently left first
    /** Places forgotten here whose bookmark the server may still hold (its delete not yet confirmed). */
    private val forgotten = LinkedHashSet<String>()

    /** What leaving a song did to its place, for the server's bookmark to follow. */
    enum class Change { KEPT, FORGOTTEN, NONE }

    /** The song was left at [positionMs] of [durationMs] (paused, skipped, another one chosen). */
    fun left(trackId: String, positionMs: Long, durationMs: Long): Change {
        if (durationMs < LONG_MS) return Change.NONE
        val had = positions.remove(trackId) != null
        if (remembers(positionMs, durationMs)) {
            positions[trackId] = positionMs
            forgotten.remove(trackId)
            trim()
            return Change.KEPT
        }
        if (!had) return Change.NONE
        forget(trackId)
        return Change.FORGOTTEN
    }

    /** Played to its end: next time it starts from the beginning. True when it had a place. */
    fun finished(trackId: String): Boolean {
        if (positions.remove(trackId) == null) return false
        forget(trackId)
        return true
    }

    /** The server confirmed the bookmark of a forgotten place is gone. */
    fun bookmarkDeleted(trackId: String) {
        forgotten.remove(trackId)
    }

    /**
     * The server's bookmarks (track id to ms), for songs with no place on this phone. Not for
     * places forgotten here (their bookmark may linger after a delete that failed), and placed
     * as the oldest, so the phone's own places are the last to go.
     */
    fun merge(bookmarks: Map<String, Long>) {
        val fromServer = bookmarks.filter { (id, ms) -> id !in positions && id !in forgotten && ms >= MARGIN_MS }
        if (fromServer.isEmpty()) return
        val own = LinkedHashMap(positions)
        positions.clear()
        positions.putAll(fromServer)
        positions.putAll(own)
        trim()
    }

    /** Where to start [trackId], [durationMs] long, when it's chosen, or null for its beginning. */
    fun resumeAt(trackId: String, durationMs: Long): Long? = positions[trackId]?.takeIf { remembers(it, durationMs) }

    fun serialize(): String =
        (positions.entries.map { (id, ms) -> "$ms\t$id" } + forgotten.map { "$FORGOTTEN_MARK\t$it" }).joinToString("\n")

    /** Reads what [serialize] wrote; lines it can't read are skipped. */
    fun load(text: String) {
        positions.clear()
        forgotten.clear()
        for (line in text.lineSequence()) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val id = line.substring(tab + 1)
            val head = line.substring(0, tab)
            if (head == FORGOTTEN_MARK) forgotten += id else head.toLongOrNull()?.let { positions[id] = it }
        }
    }

    private fun forget(trackId: String) {
        forgotten.remove(trackId)
        forgotten += trackId
        while (forgotten.size > MAX) forgotten.remove(forgotten.first())
    }

    private fun trim() {
        while (positions.size > MAX) positions.remove(positions.keys.first())
    }

    companion object {
        const val LONG_MS = 20 * 60_000L
        /** Less than a minute in, or within a minute of the end, isn't a place to come back to. */
        const val MARGIN_MS = 60_000L
        const val MAX = 100
        private const val FORGOTTEN_MARK = "-"

        fun remembers(positionMs: Long, durationMs: Long): Boolean =
            durationMs >= LONG_MS && positionMs >= MARGIN_MS && positionMs <= durationMs - MARGIN_MS
    }
}
