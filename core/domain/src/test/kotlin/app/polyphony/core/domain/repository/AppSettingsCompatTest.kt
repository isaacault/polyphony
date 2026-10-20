package app.polyphony.core.domain.repository

import app.polyphony.core.domain.model.AlbumSort
import app.polyphony.core.domain.model.ArtistSort
import app.polyphony.core.domain.model.TrackSort
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings written by older builds must still decode, with new fields taking their defaults. */
class AppSettingsCompatTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `swipe actions default to add-to-queue and nothing`() {
        val decoded = json.decodeFromString(AppSettings.serializer(), """{"appearance":{"themeMode":"DARK"}}""")
        assertEquals(ThemeMode.DARK, decoded.appearance.themeMode)
        assertEquals(TrackSwipeAction.ADD_TO_QUEUE, decoded.appearance.swipeLeftToRight)
        assertEquals(TrackSwipeAction.NONE, decoded.appearance.swipeRightToLeft)
    }

    @Test
    fun `swipe actions round-trip`() {
        val settings = AppSettings(
            appearance = AppearanceSettings(
                swipeLeftToRight = TrackSwipeAction.TOGGLE_FAVORITE,
                swipeRightToLeft = TrackSwipeAction.PLAY_NEXT,
            ),
        )
        val decoded = json.decodeFromString(AppSettings.serializer(), json.encodeToString(AppSettings.serializer(), settings))
        assertEquals(settings.appearance, decoded.appearance)
    }

    @Test
    fun `library sorts default to name order for settings written before they existed`() {
        val decoded = json.decodeFromString(AppSettings.serializer(), """{"appearance":{"themeMode":"DARK"}}""")
        assertEquals(AlbumSort.NAME, decoded.appearance.albumSort)
        assertEquals(true, decoded.appearance.albumAscending)
        assertEquals(ArtistSort.NAME, decoded.appearance.artistSort)
        assertEquals(TrackSort.TITLE, decoded.appearance.trackSort)
    }

    @Test
    fun `library sorts round-trip`() {
        val settings = AppSettings(appearance = AppearanceSettings(albumSort = AlbumSort.ARTIST, albumAscending = false, trackSort = TrackSort.ALBUM))
        val decoded = json.decodeFromString(AppSettings.serializer(), json.encodeToString(AppSettings.serializer(), settings))
        assertEquals(AlbumSort.ARTIST, decoded.appearance.albumSort)
        assertEquals(false, decoded.appearance.albumAscending)
        assertEquals(TrackSort.ALBUM, decoded.appearance.trackSort)
    }

    @Test
    fun `library opens on albums by default and remembers another choice`() {
        val legacy = json.decodeFromString(AppSettings.serializer(), """{"appearance":{"themeMode":"DARK"}}""")
        assertEquals(LibrarySection.ALBUMS, legacy.appearance.libraryDefaultTab)
        val settings = AppSettings(appearance = AppearanceSettings(libraryDefaultTab = LibrarySection.SONGS))
        val decoded = json.decodeFromString(AppSettings.serializer(), json.encodeToString(AppSettings.serializer(), settings))
        assertEquals(LibrarySection.SONGS, decoded.appearance.libraryDefaultTab)
    }
}
