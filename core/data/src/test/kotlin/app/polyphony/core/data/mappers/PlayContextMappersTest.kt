package app.polyphony.core.data.mappers

import app.polyphony.core.domain.model.PlayContext
import app.polyphony.core.domain.model.RecentContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayContextMappersTest {
    private val entry = RecentContext(
        context = PlayContext(PlayContext.Kind.PLAYLIST, "pl-1", "srv", "Road trip", subtitle = "me", artworkUrl = "http://x/a.jpg"),
        trackId = "t-9", trackTitle = "Nine", trackIndex = 8, positionMs = 42_000L, shuffle = true, updatedAt = 1_700_000_000_000L,
    )

    @Test
    fun `round trips through the entity`() {
        val entity = entry.toEntity()
        assertEquals("srv:PLAYLIST:pl-1", entity.key)
        assertEquals(entry, entity.toDomain())
    }

    @Test
    fun `favourites use an empty id but a distinct key`() {
        val fav = RecentContext(PlayContext.favorites("srv"))
        assertEquals("srv:FAVORITES:", fav.toEntity().key)
        assertEquals(fav, fav.toEntity().toDomain())
    }

    @Test
    fun `unknown kinds are dropped instead of crashing`() {
        assertNull(entry.toEntity().copy(kind = "PODCAST").toDomain())
    }
}
