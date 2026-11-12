package app.polyphony.core.domain.model

import app.polyphony.core.domain.repository.AppSettings
import app.polyphony.core.domain.repository.LibrarySettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartPlaylistTest {
    @Test fun `a rule counts once it has what its field needs`() {
        assertTrue(SmartRule(SmartField.GENRE, SmartOp.CONTAINS, "jazz").isValid)
        assertFalse("no text yet", SmartRule(SmartField.GENRE, SmartOp.CONTAINS, "  ").isValid)
        assertTrue(SmartRule(SmartField.YEAR, SmartOp.AT_LEAST, " 1990 ").isValid)
        assertFalse(SmartRule(SmartField.YEAR, SmartOp.AT_LEAST, "nineties").isValid)
        assertFalse(SmartRule(SmartField.ADDED, SmartOp.WITHIN_DAYS, "-3").isValid)
        assertTrue("a flag needs no value", SmartRule(SmartField.FAVOURITE, SmartOp.IS_TRUE).isValid)
        assertFalse("an operator from another kind of field", SmartRule(SmartField.YEAR, SmartOp.CONTAINS, "19").isValid)
    }

    @Test fun `every field offers operators of its own kind`() {
        for (field in SmartField.entries) {
            val rule = SmartRule(field, field.ops.first(), if (field.kind == SmartField.Kind.TEXT) "x" else "1")
            assertTrue("$field", rule.isValid)
        }
    }

    @Test fun `smart playlists are kept with the settings`() {
        val playlist = SmartPlaylist(
            id = "s1", name = "Fresh jazz",
            rules = listOf(SmartRule(SmartField.GENRE, SmartOp.IS, "Jazz"), SmartRule(SmartField.ADDED, SmartOp.WITHIN_DAYS, "30")),
            matchAll = false, sort = TrackSort.RECENTLY_ADDED, limit = 50,
        )
        val settings = AppSettings(library = LibrarySettings(smartPlaylists = listOf(playlist)))
        val json = Json { ignoreUnknownKeys = true }
        val back = json.decodeFromString(AppSettings.serializer(), json.encodeToString(AppSettings.serializer(), settings))
        assertEquals(listOf(playlist), back.library.smartPlaylists)
        // Settings from before smart playlists still read.
        assertEquals(emptyList<SmartPlaylist>(), json.decodeFromString(AppSettings.serializer(), """{"library":{"autoSyncHours":3}}""").library.smartPlaylists)
    }
}
