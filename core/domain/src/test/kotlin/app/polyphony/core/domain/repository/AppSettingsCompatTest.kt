package app.polyphony.core.domain.repository

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
}
