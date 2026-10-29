package app.polyphony.core.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FtsQueryTest {
    @Test
    fun `tokens become prefix matches`() {
        assertEquals("the* beat* les*", FtsQuery.match("the  beat-les!"))
    }

    @Test
    fun `fts operators and quotes are stripped`() {
        assertEquals("daft* or* punk* near*", FtsQuery.match("\"daft\" OR* punk:NEAR("))
    }

    @Test
    fun `unicode letters survive`() {
        assertEquals("björk* 日本*", FtsQuery.match("Björk 日本"))
    }

    @Test
    fun `nothing searchable gives null`() {
        assertNull(FtsQuery.match("  *** ---  "))
    }

    @Test
    fun `token count is capped`() {
        val many = (1..20).joinToString(" ") { "w$it" }
        assertEquals(8, FtsQuery.match(many)!!.split(" ").size)
    }

    @Test
    fun `like pattern escapes wildcards`() {
        assertEquals("%100\\% pure\\_gold%", FtsQuery.likePattern(" 100% pure_gold "))
        assertEquals("%a\\\\b%", FtsQuery.likePattern("a\\b"))
    }
}
