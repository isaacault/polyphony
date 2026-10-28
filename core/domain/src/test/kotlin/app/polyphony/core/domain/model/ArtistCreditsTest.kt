package app.polyphony.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistCreditsTest {
    @Test fun `a tag names each credited artist`() {
        assertEquals(listOf("Drake", "Rihanna", "Future"), ArtistCredits.names("Drake feat. Rihanna & Future"))
        assertEquals(listOf("Calvin Harris", "Dua Lipa"), ArtistCredits.names("Calvin Harris, Dua Lipa"))
        assertEquals(listOf("Skrillex", "Fred again.."), ArtistCredits.names("Skrillex x Fred again.."))
    }

    @Test fun `credited, not just named`() {
        assertTrue(ArtistCredits.credits("Drake feat. Rihanna", "Rihanna"))
        assertTrue(ArtistCredits.credits("Calvin Harris ft. Rihanna", "rihanna"))
        assertFalse(ArtistCredits.credits("Rihanna Tribute Band", "Rihanna"))
        assertFalse(ArtistCredits.credits("Yeah Yeah Yeahs", "Ye"))
        assertFalse(ArtistCredits.credits(null, "Drake"))
    }

    @Test fun `a name with a joiner of its own counts whole`() {
        assertTrue(ArtistCredits.credits("Simon & Garfunkel", "Simon & Garfunkel"))
        assertTrue(ArtistCredits.credits("Paul Simon, Simon & Garfunkel", "Simon & Garfunkel"))
        assertFalse(ArtistCredits.credits("Simon & Garfunkel Revival", "Simon & Garfunkel"))
    }
}
