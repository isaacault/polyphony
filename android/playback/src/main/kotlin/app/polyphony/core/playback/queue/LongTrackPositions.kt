package app.polyphony.core.playback.queue

/**
 * Where long songs (mixes, live sets, audiobooks: [LONG_MS] and more) were left, so choosing one
 * again picks up there (#37). On the phone, the newest [MAX] of them, with the server's bookmarks
 * filling in songs this phone has no place for. Not thread-safe: the playback service uses it on
 * the main thread and writes [serialize] to a file.
 */
class LongTrackPositions {
    private val positions = LinkedHashMap<String, Long>()   // track id -> ms, least recently left first
    /**
     * Places forgotten here whose bookmark the server may still hold (its delete not yet
     * confirmed): track id to the position that bookmark has.
     */
    private val forgotten = LinkedHashMap<String, Long>()

    /** What leaving a song did to its place, for the server's bookmark to follow. */
    enum class Change { KEPT, FORGOTTEN, NONE }

    /** The song was left at [positionMs] of [durationMs] (paused, skipped, another one chosen). */
    fun left(trackId: String, positionMs: Long, durationMs: Long): Change {
        if (durationMs < LONG_MS) return Change.NONE
        val had = positions.remove(trackId)
        if (remembers(positionMs, durationMs)) {
            positions[trackId] = positionMs
            forgotten.remove(trackId)
            trim()
            return Change.KEPT
        }
        if (had == null) return Change.NONE
        forget(trackId, had)
        return Change.FORGOTTEN
    }

    /** Played to its end: next time it starts from the beginning. True when it had a place. */
    fun finished(trackId: String): Boolean {
        val had = positions.remove(trackId) ?: return false
        forget(trackId, had)
        return true
    }

    /** The server confirmed the bookmark of a forgotten place is gone. */
    fun bookmarkDeleted(trackId: String) {
        forgotten.remove(trackId)
    }

    /**
     * The server's bookmarks (track id to ms), for songs with no place on this phone, placed as
     * the oldest so the phone's own places are the last to go. A bookmark still at a place
     * forgotten here is the one whose delete failed: it isn't taken, and its id is returned to be
     * deleted again. One at another place was left on another device since, and is taken.
     */
    fun merge(bookmarks: Map<String, Long>): List<String> {
        val stale = ArrayList<String>()
        val fromServer = LinkedHashMap<String, Long>()
        for ((id, ms) in bookmarks) {
            if (id in positions || ms < MARGIN_MS) continue
            val gone = forgotten[id]
            if (gone != null) {
                if (gone == ms) {
                    stale += id
                    continue
                }
                forgotten.remove(id)
            }
            fromServer[id] = ms
        }
        if (fromServer.isNotEmpty()) {
            val own = LinkedHashMap(positions)
            positions.clear()
            positions.putAll(fromServer)
            positions.putAll(own)
            trim()
        }
        return stale
    }

    /** Where to start [trackId], [durationMs] long, when it's chosen, or null for its beginning. */
    fun resumeAt(trackId: String, durationMs: Long): Long? = positions[trackId]?.takeIf { remembers(it, durationMs) }

    fun serialize(): String =
        (positions.entries.map { (id, ms) -> "$ms\t$id" } + forgotten.entries.map { (id, ms) -> "$FORGOTTEN_MARK$ms\t$id" })
            .joinToString("\n")

    /** Reads what [serialize] wrote; lines it can't read are skipped. */
    fun load(text: String) {
        positions.clear()
        forgotten.clear()
        for (line in text.lineSequence()) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val id = line.substring(tab + 1)
            val head = line.substring(0, tab)
            if (head.startsWith(FORGOTTEN_MARK)) {
                forgotten[id] = head.removePrefix(FORGOTTEN_MARK).toLongOrNull() ?: -1L
            } else {
                head.toLongOrNull()?.let { positions[id] = it }
            }
        }
    }

    private fun forget(trackId: String, positionMs: Long) {
        forgotten.remove(trackId)
        forgotten[trackId] = positionMs
        while (forgotten.size > MAX) forgotten.remove(forgotten.keys.first())
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
