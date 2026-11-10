package app.polyphony.core.playback.queue

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * A small text file that a kill at any moment leaves holding the old contents or the new, never a
 * mix, and that overlapping writers can't corrupt.
 *
 * - Each write goes to a temporary file, is flushed to the disk (fsync: a power cut right after the
 *   rename can't leave an empty file) and replaces the file by a rename, which is atomic.
 * - Writes are serialised and the newest wins: a caller takes a [ticket] when it takes its snapshot,
 *   and a write whose ticket is older than one already written is dropped. The queue is saved from
 *   a timer, from a debounced change and from onDestroy; two of them used to write the same
 *   temporary file at once, and one longer than the other left a file with the tail of both.
 * - Reading falls back to the temporary file when the file itself is missing or unreadable (a
 *   replace that was cut off between deleting the old file and renaming the new one).
 *
 * Plain java.io; unit-tested.
 */
class AtomicTextFile(private val file: File) {
    private val tmp = File(file.parentFile, file.name + ".tmp")
    private val issued = AtomicLong()
    private val lock = Any()
    /** The newest ticket written or cleared; guarded by [lock]. */
    private var done = 0L

    /** This write's place in line. Take it where the snapshot is taken, in the snapshot's order. */
    fun ticket(): Long = issued.incrementAndGet()

    /** Writes [text] unless a newer ticket was written (or cleared) first. True when it was written. */
    fun write(ticket: Long, text: String): Boolean = synchronized(lock) {
        if (ticket <= done) return false
        file.parentFile?.mkdirs()
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            // Not on Android's filesystems; a replace that can't overwrite. Until the rename, the
            // complete temporary file is what read() finds.
            file.delete()
            if (!tmp.renameTo(file)) return false
        }
        done = ticket
        true
    }

    /** Removes the file unless a newer ticket was written first. */
    fun clear(ticket: Long): Boolean = synchronized(lock) {
        if (ticket <= done) return false
        file.delete()
        tmp.delete()
        done = ticket
        true
    }

    /** The file's contents through [parse], or the temporary file's when the file is missing or [parse] fails. */
    fun <T : Any> read(parse: (String) -> T?): T? = synchronized(lock) {
        for (candidate in listOf(file, tmp)) {
            if (!candidate.exists()) continue
            val text = runCatching { candidate.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            runCatching { parse(text) }.getOrNull()?.let { return it }
        }
        null
    }
}
