package app.polyphony.core.playback.queue

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder
import java.util.Random

/**
 * ExoPlayer's shuffle order, with songs you queue kept next (UserQueue): a fresh list is shuffled
 * at random, but songs inserted into a playing list go straight after the song before them in the
 * shuffled order too (after the current one for Play next, after your last for Add to queue), so
 * shuffle mixes the album or playlist, never what you queued. DefaultShuffleOrder would scatter them.
 */
@UnstableApi
class QueueShuffleOrder private constructor(private val shuffled: IntArray, private val random: Random) : ShuffleOrder {

    constructor(seed: Long = System.nanoTime()) : this(IntArray(0), Random(seed))

    private val position = IntArray(shuffled.size).also { pos -> shuffled.forEachIndexed { i, index -> pos[index] = i } }

    override fun getLength(): Int = shuffled.size

    override fun getNextIndex(index: Int): Int {
        val p = position[index] + 1
        return if (p < shuffled.size) shuffled[p] else androidx.media3.common.C.INDEX_UNSET
    }

    override fun getPreviousIndex(index: Int): Int {
        val p = position[index] - 1
        return if (p >= 0) shuffled[p] else androidx.media3.common.C.INDEX_UNSET
    }

    override fun getLastIndex(): Int = if (shuffled.isEmpty()) androidx.media3.common.C.INDEX_UNSET else shuffled.last()

    override fun getFirstIndex(): Int = if (shuffled.isEmpty()) androidx.media3.common.C.INDEX_UNSET else shuffled.first()

    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder {
        if (insertionCount == 0) return this
        if (shuffled.isEmpty()) {
            // A fresh list: shuffle it.
            val fresh = IntArray(insertionCount) { it }
            for (i in fresh.indices.reversed()) {
                val j = random.nextInt(i + 1)
                val t = fresh[i]; fresh[i] = fresh[j]; fresh[j] = t
            }
            return QueueShuffleOrder(fresh, random)
        }
        // Existing indices at or past the insertion move up; the new ones go, in order, straight
        // after the item before the insertion point in the shuffled order (the front if none).
        val moved = shuffled.map { if (it >= insertionIndex) it + insertionCount else it }
        val before = insertionIndex - 1
        val at = if (before < 0) 0 else moved.indexOf(before) + 1
        val out = ArrayList<Int>(moved.size + insertionCount)
        out.addAll(moved.subList(0, at))
        for (k in 0 until insertionCount) out += insertionIndex + k
        out.addAll(moved.subList(at, moved.size))
        return QueueShuffleOrder(out.toIntArray(), random)
    }

    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder {
        val count = indexToExclusive - indexFrom
        val out = shuffled.filter { it < indexFrom || it >= indexToExclusive }.map { if (it >= indexToExclusive) it - count else it }
        return QueueShuffleOrder(out.toIntArray(), random)
    }

    override fun cloneAndClear(): ShuffleOrder = QueueShuffleOrder(IntArray(0), random)

    /** For tests: the shuffled order as list indices. */
    internal fun order(): List<Int> = shuffled.toList()
}
