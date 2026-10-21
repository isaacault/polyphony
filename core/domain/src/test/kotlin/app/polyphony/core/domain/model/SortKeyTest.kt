package app.polyphony.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SortKeyTest {

    @Test
    fun `leading articles are skipped`() {
        assertEquals("Strokes", sortKey("The Strokes"))
        assertEquals("Tribe Called Quest", sortKey("A Tribe Called Quest"))
        assertEquals("Ocean Blue", sortKey("an Ocean Blue"))
    }

    @Test
    fun `articles only count as a whole word`() {
        assertEquals("Theory of a Deadman", sortKey("Theory of a Deadman"))
        assertEquals("Abba", sortKey("Abba"))
        assertEquals("A-ha", sortKey("A-ha"))
        assertEquals("Anthrax", sortKey("Anthrax"))
    }

    @Test
    fun `a bare article is a name, not a prefix`() {
        assertEquals("The", sortKey("The"))
        assertEquals("A", sortKey(" A "))
        assertEquals("The", sortKey("The The"))
    }

    @Test
    fun `a real server sort name wins and is stripped the same way`() {
        assertEquals("Beatles", sortKey("The Beatles", sortName = "Beatles"))
        assertEquals("Strokes", sortKey("The Strokes", sortName = "The Strokes"))
        assertEquals("Strokes", sortKey("The Strokes", sortName = "  "))
        assertEquals("", sortKey(null))
    }

    @Test
    fun `the rail files stripped names under their real letter`() {
        assertEquals('T', scrollerLetter(sortKey("A Tribe Called Quest")))
        assertEquals('S', scrollerLetter(sortKey("The Strokes")))
    }
}
