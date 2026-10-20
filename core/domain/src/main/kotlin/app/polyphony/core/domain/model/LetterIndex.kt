package app.polyphony.core.domain.model

/** Bucket a display name into an alphabet-rail letter: A–Z, or '#' for anything else (digits, symbols, blank). */
fun scrollerLetter(name: String?): Char {
    val c = name?.trim()?.firstOrNull { !it.isWhitespace() }?.uppercaseChar() ?: return '#'
    return if (c in 'A'..'Z') c else '#'
}

/**
 * Where each alphabet-rail letter starts in a sorted list, built from the sort keys alone so the
 * rows themselves never have to be loaded. Pairs with a paged list that reports the same [size].
 *
 * @property available letters that have at least one item, in list order.
 * @property firstIndex position of the first item for each letter in [available].
 */
data class LetterIndex(
    val available: List<Char>,
    val firstIndex: Map<Char, Int>,
    val size: Int,
) {
    /**
     * Index to scroll to for [letter]: its first item, else the first item of the next letter that
     * is present, else the end of the list. '#' is always the top.
     */
    fun indexFor(letter: Char): Int {
        if (size == 0 || letter == '#') return 0
        firstIndex[letter]?.let { return it }
        val next = available.filter { it != '#' && it > letter }.minOrNull()
        return next?.let { firstIndex[it] } ?: (size - 1)
    }

    companion object {
        val EMPTY = LetterIndex(emptyList(), emptyMap(), 0)

        /** [keys] are the sort-column values in list order, one per row (the first character is enough). */
        fun of(keys: List<String?>): LetterIndex {
            if (keys.isEmpty()) return EMPTY
            val first = LinkedHashMap<Char, Int>()
            keys.forEachIndexed { index, key ->
                val letter = scrollerLetter(key)
                if (letter !in first) first[letter] = index
            }
            return LetterIndex(first.keys.toList(), first, keys.size)
        }
    }
}
