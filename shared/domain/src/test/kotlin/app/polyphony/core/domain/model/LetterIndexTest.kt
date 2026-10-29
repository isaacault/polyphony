package app.polyphony.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LetterIndexTest {

    private val index = LetterIndex.of(listOf("1st", "apple", "Avocado", "banana", "cherry", " date", null))

    @Test
    fun `records the first position of each letter in list order`() {
        assertEquals(listOf('#', 'A', 'B', 'C', 'D'), index.available)
        assertEquals(1, index.firstIndex['A'])
        assertEquals(3, index.firstIndex['B'])
        assertEquals(5, index.firstIndex['D'])
        assertEquals(7, index.size)
    }

    @Test
    fun `hash always jumps to the top`() {
        assertEquals(0, index.indexFor('#'))
    }

    @Test
    fun `a present letter jumps to its first item`() {
        assertEquals(4, index.indexFor('C'))
    }

    @Test
    fun `a missing letter falls through to the next letter that is present`() {
        val sparse = LetterIndex.of(listOf("alpha", "charlie", "echo"))
        assertEquals(1, sparse.indexFor('B'))
        assertEquals(2, sparse.indexFor('D'))
    }

    @Test
    fun `letters past the last present letter go to the end`() {
        assertEquals(index.size - 1, index.indexFor('Z'))
    }

    @Test
    fun `empty index is safe`() {
        assertEquals(0, LetterIndex.EMPTY.indexFor('M'))
        assertEquals(LetterIndex.EMPTY, LetterIndex.of(emptyList()))
    }

    @Test
    fun `scrollerLetter buckets digits symbols and blanks into hash`() {
        assertEquals('#', scrollerLetter("1999"))
        assertEquals('#', scrollerLetter("(Untitled)"))
        assertEquals('#', scrollerLetter("   "))
        assertEquals('#', scrollerLetter(null))
        assertEquals('É'.let { '#' }, scrollerLetter("Étoile"))
        assertEquals('Q', scrollerLetter("  quiet"))
    }
}
