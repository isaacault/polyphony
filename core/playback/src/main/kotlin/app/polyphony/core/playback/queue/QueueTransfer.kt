package app.polyphony.core.playback.queue

import kotlin.random.Random

/** Pure queue arithmetic shared by the service and the controller; unit-tested. */
object QueueMath {
    /** Insertion index so that items land directly after the current one. */
    fun insertAfterCurrentIndex(queueSize: Int, currentIndex: Int): Int =
        if (queueSize == 0 || currentIndex < 0) queueSize else (currentIndex + 1).coerceAtMost(queueSize)

    /** Index to start from when shuffling a fresh queue. */
    fun shuffledStartIndex(size: Int, random: Random = Random.Default): Int =
        if (size <= 1) 0 else random.nextInt(size)

    /** Where the current item lands after moving [from] → [to]. */
    fun currentIndexAfterMove(current: Int, from: Int, to: Int): Int = when {
        current == from -> to
        from < current && to >= current -> current - 1
        from > current && to <= current -> current + 1
        else -> current
    }

    /** Where the current item lands after removing [removed]; -1 if the queue becomes empty. */
    fun currentIndexAfterRemove(current: Int, removed: Int, sizeBefore: Int): Int = when {
        sizeBefore <= 1 -> -1
        removed < current -> current - 1
        removed == current -> current.coerceAtMost(sizeBefore - 2)
        else -> current
    }
}
