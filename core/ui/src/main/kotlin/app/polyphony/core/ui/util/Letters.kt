package app.polyphony.core.ui.util

import app.polyphony.core.ui.components.scrollerLetter
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * Index to scroll to for [letter] in a list sorted by [name]: the first item with that letter, or
 * the first item with the next available letter, or the end of the list.
 */
fun <T> letterIndex(items: List<T>, letter: Char, name: (T) -> String?): Int {
    if (items.isEmpty()) return 0
    if (letter == '#') return 0
    var fallback = -1
    for ((index, item) in items.withIndex()) {
        val l = scrollerLetter(name(item))
        if (l == letter) return index
        if (l > letter && l != '#' && fallback == -1) fallback = index
    }
    return if (fallback >= 0) fallback else items.lastIndex
}

fun <T> availableLetters(items: List<T>, name: (T) -> String?): ImmutableList<Char> =
    items.asSequence().map { scrollerLetter(name(it)) }.distinct().toList().toImmutableList()
