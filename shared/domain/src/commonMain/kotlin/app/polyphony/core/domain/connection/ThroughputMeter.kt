package app.polyphony.core.domain.connection

/**
 * Link throughput from the reads of streams coming off the network: bytes over the time spent
 * getting them. Not thread-safe; the caller serialises calls. Timestamps are monotonic nanoseconds.
 *
 * Two things make the plain sum of read times wrong, and are handled here:
 * - The player stops reading once its buffer is full and resumes much later, while the socket keeps
 *   receiving. The first reads after such a pause (a gap over [pauseGapNs] since the stream's last
 *   read) return what the socket already held, instantly; they are skipped until a read has to wait
 *   for the network again (it takes at least [blockedReadNs]).
 * - The player and the fetching ahead read at the same time, sharing the link: time is counted once
 *   however many streams read in it.
 *
 * Within a run of reads the time between them (the player parsing what it read) counts too, so a
 * link faster than the player is measured at the rate the player took it: a lower bound, which is
 * what matters on a slow link.
 */
class ThroughputMeter(
    private val pauseGapNs: Long = 250_000_000L,
    private val blockedReadNs: Long = 2_000_000L,
) {
    private class Stream {
        var lastReadEndNs: Long = NONE
        var skipping: Boolean = false
    }

    private val streams = HashMap<Long, Stream>()
    private var coveredUntilNs: Long = Long.MIN_VALUE

    /** Bytes counted since the last [reset]. */
    var bytes: Long = 0
        private set

    /** Time counted since the last [reset]. */
    var busyNs: Long = 0
        private set

    /** Stream [id] was (re)opened: its first read starts a fresh run. */
    fun onOpened(id: Long) {
        streams[id] = Stream()
    }

    fun onClosed(id: Long) {
        streams.remove(id)
    }

    /** One read on stream [id] that took from [startNs] to [endNs] and returned [count] bytes. */
    fun onRead(id: Long, startNs: Long, endNs: Long, count: Int) {
        val stream = streams.getOrPut(id) { Stream() }
        val previousEnd = stream.lastReadEndNs
        stream.lastReadEndNs = endNs
        if (count <= 0) return
        var from = when {
            previousEnd == NONE -> startNs
            startNs - previousEnd > pauseGapNs -> {
                stream.skipping = true
                startNs
            }
            else -> previousEnd
        }
        if (stream.skipping) {
            // What the socket held while the player wasn't reading: no measure of the link.
            if (endNs - startNs < blockedReadNs) return
            stream.skipping = false
            from = startNs
        }
        val begin = maxOf(from, coveredUntilNs)
        if (endNs > begin) busyNs += endNs - begin
        if (endNs > coveredUntilNs) coveredUntilNs = endNs
        bytes += count
    }

    /** Throughput in kbit/s since the last [reset]; null when too little was read to tell. */
    fun throughputKbps(): Int? = throughputKbps(bytes, busyNs)

    /** Starts counting afresh (a new window); streams in progress carry on. */
    fun reset() {
        bytes = 0
        busyNs = 0
    }

    companion object {
        private const val NONE = Long.MIN_VALUE

        /** Below this many bytes a window says nothing about the link. */
        const val MIN_BYTES = 32L * 1024
        const val MIN_BUSY_NS = 200_000_000L

        fun throughputKbps(bytes: Long, busyNs: Long): Int? {
            if (bytes < MIN_BYTES || busyNs < MIN_BUSY_NS) return null
            return (bytes * 8_000_000L / busyNs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }
}
