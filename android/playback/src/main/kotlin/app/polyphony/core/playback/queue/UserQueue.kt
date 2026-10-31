package app.polyphony.core.playback.queue

/**
 * The queue as Spotify has it (contract/cases/queue.json): after the song that's playing, first
 * "Next in queue", the songs you added (Play next, Add to queue), then "Next from" the album,
 * playlist or list you pressed play on. The player's list stays flat: each song you add carries a
 * flag (MediaItemFactory.EXTRA_QUEUED), and yours are the flagged ones right after the current.
 *
 * - Play next puts songs at the front of yours; Add to queue at the back of yours (with none of
 *   yours waiting, the two are the same: straight after the current song).
 * - Playing something new keeps yours: they still play next, then the new list.
 * Pure: the controller and the service use these on the player's state; unit-tested.
 */
object UserQueue {
    /** How many of yours wait right after [current] (the flagged run after it). */
    fun waiting(flags: List<Boolean>, current: Int): Int {
        var n = 0
        var i = current + 1
        while (i in flags.indices && flags[i]) {
            n++
            i++
        }
        return n
    }

    /** Where songs go in the flat list: Play next straight after the current, Add to queue after yours. */
    fun insertIndex(flags: List<Boolean>, current: Int, playNext: Boolean): Int {
        if (flags.isEmpty() || current < 0) return flags.size
        val after = (current + 1).coerceAtMost(flags.size)
        return if (playNext) after else (after + waiting(flags, current)).coerceAtMost(flags.size)
    }

    /**
     * Playing a new list from [start] while some of yours wait: the new list with yours straight after
     * its first song. Returns the new list's (item, flag) pairs; the new current is [start].
     */
    fun <T> withWaiting(newList: List<T>, start: Int, waiting: List<T>): List<Pair<T, Boolean>> {
        if (waiting.isEmpty()) return newList.map { it to false }
        val s = start.coerceIn(0, newList.lastIndex.coerceAtLeast(0))
        val out = ArrayList<Pair<T, Boolean>>(newList.size + waiting.size)
        newList.forEachIndexed { i, item ->
            out += item to false
            if (i == s) waiting.forEach { out += it to true }
        }
        if (newList.isEmpty()) waiting.forEach { out += it to true }
        return out
    }
}
