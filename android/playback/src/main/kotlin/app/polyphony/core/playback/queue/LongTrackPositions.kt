package app.polyphony.core.playback.queue

/**
 * Where long songs (mixes, live sets, audiobooks: [LONG_MS] and more) were left, so choosing one
 * again picks up there (#37). On the phone, the newest [MAX] of them. Not thread-safe: the
 * playback service uses it on the main thread and writes [serialize] to a file.
 */
class LongTrackPositions {
    private val positions = LinkedHashMap<String, Long>()   // track id -> ms, least recently left first

    /** What leaving a song did to its place, for the server's bookmark to follow. */
    enum class Change { KEPT, FORGOTTEN, NONE }

    /** The song was left at [positionMs] of [durationMs] (paused, skipped, another one chosen). */
    fun left(trackId: String, positionMs: Long, durationMs: Long): Change {
        if (durationMs < LONG_MS) return Change.NONE
        val had = positions.remove(trackId) != null
        if (remembers(positionMs, durationMs)) {
            positions[trackId] = positionMs
            while (positions.size > MAX) positions.remove(positions.keys.first())
            return Change.KEPT
        }
        return if (had) Change.FORGOTTEN else Change.NONE
    }

    /** Played to its end: next time it starts from the beginning. True when it had a place. */
    fun finished(trackId: String): Boolean = positions.remove(trackId) != null

    /** The server's bookmarks (track id to ms), for songs with no place on this phone. */
    fun merge(bookmarks: Map<String, Long>) {
        for ((id, ms) in bookmarks) if (id !in positions && ms >= MARGIN_MS) positions[id] = ms
        while (positions.size > MAX) positions.remove(positions.keys.first())
    }

    /** Where to start [trackId] when it's chosen, or null for its beginning. */
    fun resumeAt(trackId: String, durationMs: Long): Long? =
        positions[trackId]?.takeIf { durationMs <= 0 || remembers(it, durationMs) }

    fun serialize(): String = positions.entries.joinToString("\n") { (id, ms) -> "$ms\t$id" }

    /** Reads what [serialize] wrote; lines it can't read are skipped. */
    fun load(text: String) {
        positions.clear()
        for (line in text.lineSequence()) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val ms = line.substring(0, tab).toLongOrNull() ?: continue
            positions[line.substring(tab + 1)] = ms
        }
    }

    companion object {
        const val LONG_MS = 20 * 60_000L
        /** Less than a minute in, or within a minute of the end, isn't a place to come back to. */
        const val MARGIN_MS = 60_000L
        const val MAX = 100

        fun remembers(positionMs: Long, durationMs: Long): Boolean =
            durationMs >= LONG_MS && positionMs >= MARGIN_MS && positionMs <= durationMs - MARGIN_MS
    }
}
