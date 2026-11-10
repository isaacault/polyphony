package app.polyphony.core.playback.queue

import app.polyphony.core.domain.model.PlayContext
import app.polyphony.core.domain.model.Track

/**
 * What is saved of the player's queue for a restart ([PersistedQueue]). Pure; unit-tested.
 *
 * An item whose track can't be known (an item another Cast sender loaded) isn't saved, and the
 * index, the songs you queued and the shuffle order are renumbered around the gap: the index used to
 * be the player's own, so after a gap it pointed at the song after the one playing.
 */
object QueueSnapshot {
    /** One item of the player's list: its track, null when it can't be known, and whether you queued it. */
    data class Item(val track: Track?, val queued: Boolean = false)

    fun of(
        items: List<Item>,
        currentIndex: Int,
        positionMs: Long,
        /** The player's shuffle order over [items] (see [QueueShuffleOrder.playOrder]), null when unknown. */
        shuffleOrder: List<Int>?,
        shuffle: Boolean,
        repeatMode: Int,
        queueTitle: String?,
        context: PlayContext?,
        savedAt: Long,
    ): PersistedQueue? {
        val kept = Kept(items.size) { items[it].track != null }
        val (index, keptItself) = kept.current(currentIndex) ?: return null
        return PersistedQueue(
            tracks = kept.old.map { items[it].track!! },
            index = index,
            // A song that couldn't be saved gives its place to the next one, from its start.
            positionMs = if (keptItself) positionMs.coerceAtLeast(0L) else 0L,
            shuffle = shuffle,
            repeatMode = repeatMode,
            queueTitle = queueTitle,
            savedAt = savedAt,
            context = context,
            queuedIndices = kept.old.filter { items[it].queued }.map(kept::newIndex),
            shuffleOrder = kept.order(shuffleOrder),
        )
    }

    /** Whether [order] holds each index of a [size]-item list exactly once. */
    fun isPermutation(order: List<Int>, size: Int): Boolean {
        if (order.size != size) return false
        val seen = BooleanArray(size)
        for (i in order) {
            if (i !in 0 until size || seen[i]) return false
            seen[i] = true
        }
        return true
    }
}

/** Some of a [size]-item list's items kept, in order: where each lands. */
internal class Kept(private val size: Int, keep: (Int) -> Boolean) {
    /** The kept items' old indices, in order. */
    val old: List<Int> = (0 until size).filter(keep)
    private val new = IntArray(size) { -1 }.also { a -> old.forEachIndexed { n, o -> a[o] = n } }

    /** Where the item at [oldIndex] lands; -1 when it was dropped. */
    fun newIndex(oldIndex: Int): Int = if (oldIndex in 0 until size) new[oldIndex] else -1

    /**
     * Where the current item [oldIndex] lands, and whether it was kept itself: if it was dropped,
     * the next kept item takes its place (the last when none follows). Null when nothing is kept.
     */
    fun current(oldIndex: Int): Pair<Int, Boolean>? {
        if (old.isEmpty()) return null
        newIndex(oldIndex).takeIf { it >= 0 }?.let { return it to true }
        val next = old.firstOrNull { it > oldIndex } ?: old.last()
        return new[next] to false
    }

    /** A shuffle order over the old list, renumbered over the kept items; empty when it wasn't a whole order. */
    fun order(order: List<Int>?): List<Int> {
        if (order == null || !QueueSnapshot.isPermutation(order, size)) return emptyList()
        return order.mapNotNull { newIndex(it).takeIf { n -> n >= 0 } }
    }
}
