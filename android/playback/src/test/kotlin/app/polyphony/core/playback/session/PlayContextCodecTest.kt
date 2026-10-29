package app.polyphony.core.playback.session

import app.polyphony.core.domain.model.PlayContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayContextCodecTest {
    @Test
    fun `round trips every field`() {
        val context = PlayContext(PlayContext.Kind.GENRE, "Jazz", "srv", "Jazz", subtitle = null, artworkUrl = "http://x/j.jpg")
        assertEquals(context, PlayContextCodec.decode(PlayContextCodec.encode(context)))
    }

    @Test
    fun `null and garbage decode to null`() {
        assertNull(PlayContextCodec.encode(null))
        assertNull(PlayContextCodec.decode(null))
        assertNull(PlayContextCodec.decode(""))
        assertNull(PlayContextCodec.decode("{not json"))
        assertNull(PlayContextCodec.decode("""{"kind":"PODCAST","id":"x","serverId":"s","title":"t"}"""))
    }
}
