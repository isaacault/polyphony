package app.polyphony.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicBrainzIdsTest {

    private val boc = "69158f97-4c07-4c4e-baf8-4e4ab1ed666e"

    @Test
    fun `an empty or missing id is no id`() {
        assertNull(MusicBrainzIds.first(""))
        assertNull(MusicBrainzIds.first("   "))
        assertNull(MusicBrainzIds.first(null))
        assertNull(MusicBrainzIds.first("not-an-id"))
    }

    @Test
    fun `the first real id is kept, lower-cased`() {
        assertEquals(boc, MusicBrainzIds.first(boc))
        assertEquals(boc, MusicBrainzIds.first(boc.uppercase()))
        assertEquals(boc, MusicBrainzIds.first("$boc;0383dadf-2a4e-4d10-a46a-e9e041da8eb3"))
        assertEquals(boc, MusicBrainzIds.first(" $boc "))
    }
}
