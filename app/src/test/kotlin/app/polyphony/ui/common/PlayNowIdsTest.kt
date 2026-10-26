package app.polyphony.ui.common

import app.polyphony.core.domain.model.PlayNowIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayNowIdsTest {
    @Test
    fun `a play-now track id carries its stream and a library id doesn't`() {
        assertEquals("abc", PlayNowIds.streamId(PlayNowIds.of("abc")))
        assertNull(PlayNowIds.streamId("al-123"))
    }
}
