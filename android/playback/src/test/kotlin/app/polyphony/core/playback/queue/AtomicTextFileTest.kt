package app.polyphony.core.playback.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AtomicTextFileTest {
    @get:Rule val folder = TemporaryFolder()

    private val target get() = File(folder.root, "playback/queue.json")
    private val tmp get() = File(folder.root, "playback/queue.json.tmp")

    /** A "parser" that only accepts complete payloads, as JSON decoding does. */
    private fun complete(text: String): String? = text.takeIf { it.startsWith("<") && it.endsWith(">") }

    @Test fun `writes and reads back, creating the folder`() {
        val f = AtomicTextFile(target)
        assertTrue(f.write(f.ticket(), "<one>"))
        assertEquals("<one>", f.read(::complete))
        assertFalse("no temporary file is left behind", tmp.exists())
    }

    @Test fun `nothing saved reads as nothing`() {
        assertNull(AtomicTextFile(target).read(::complete))
    }

    @Test fun `killed mid-write, the last complete save is what comes back`() {
        val f = AtomicTextFile(target)
        f.write(f.ticket(), "<old queue>")
        // The process dies while the next save is half written into the temporary file.
        tmp.writeText("<new que")
        assertEquals("<old queue>", AtomicTextFile(target).read(::complete))
    }

    @Test fun `killed between removing the old file and renaming the new one, the new one is found`() {
        target.parentFile!!.mkdirs()
        tmp.writeText("<new queue>")
        assertEquals("<new queue>", AtomicTextFile(target).read(::complete))
    }

    @Test fun `a corrupt file falls back to a complete temporary one`() {
        target.parentFile!!.mkdirs()
        target.writeText("<trunc")
        tmp.writeText("<whole>")
        assertEquals("<whole>", AtomicTextFile(target).read(::complete))
    }

    @Test fun `an older snapshot finishing late never overwrites a newer one`() {
        val f = AtomicTextFile(target)
        val older = f.ticket()
        val newer = f.ticket()
        assertTrue(f.write(newer, "<newer>"))
        assertFalse(f.write(older, "<older, and much longer than the newer one>"))
        assertEquals("<newer>", f.read(::complete))
    }

    @Test fun `a stale save can't bring back a cleared queue`() {
        val f = AtomicTextFile(target)
        val stale = f.ticket()
        f.clear(f.ticket())
        assertFalse(f.write(stale, "<stale>"))
        assertNull(f.read(::complete))
        assertFalse(target.exists())
    }

    @Test fun `a save after a clear is kept`() {
        val f = AtomicTextFile(target)
        f.write(f.ticket(), "<a>")
        f.clear(f.ticket())
        f.write(f.ticket(), "<b>")
        assertEquals("<b>", f.read(::complete))
    }

    @Test fun `overlapping writers of different lengths leave one whole payload, the newest`() {
        val f = AtomicTextFile(target)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val tickets = (1..200).map { f.ticket() }
        tickets.forEach { t ->
            pool.execute {
                start.await()
                // Lengths vary a lot, so an interleaving would show as a mixed or broken payload.
                f.write(t, "<" + "x".repeat((t % 7 * 500).toInt()) + t + ">")
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        val last = tickets.last()
        assertEquals("<" + "x".repeat((last % 7 * 500).toInt()) + last + ">", f.read(::complete))
    }
}
