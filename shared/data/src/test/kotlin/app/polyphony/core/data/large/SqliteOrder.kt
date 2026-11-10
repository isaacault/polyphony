package app.polyphony.core.data.large

import org.junit.Assert.assertTrue

/**
 * SQLite's NOCASE order: ASCII letters folded, everything else compared as UTF-8 bytes. Kotlin's
 * case-insensitive comparison folds "Ö" too, so it cannot check a SQLite-sorted list.
 */
val NOCASE: Comparator<String> = Comparator { a, b ->
    val x = a.foldAscii().encodeToByteArray()
    val y = b.foldAscii().encodeToByteArray()
    for (i in 0 until minOf(x.size, y.size)) {
        val d = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
        if (d != 0) return@Comparator d
    }
    x.size - y.size
}

private fun String.foldAscii(): String = String(CharArray(length) { i -> this[i].let { if (it in 'A'..'Z') it + 32 else it } })

/** [keys] never go backwards under [comparator]: the list is sorted (ties in any order). */
fun <T> assertSorted(label: String, keys: List<T>, comparator: Comparator<in T>) {
    val bad = keys.zipWithNext().indexOfFirst { (a, b) -> comparator.compare(a, b) > 0 }
    assertTrue("$label: out of order at $bad: ${keys.getOrNull(bad)} before ${keys.getOrNull(bad + 1)}", bad < 0)
}
